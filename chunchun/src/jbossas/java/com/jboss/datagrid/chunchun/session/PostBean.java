/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat Middleware LLC, and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package com.jboss.datagrid.chunchun.session;

import java.io.Serializable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.enterprise.context.SessionScoped;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.inject.Named;
import javax.transaction.UserTransaction;

import com.jboss.datagrid.chunchun.model.Post;
import org.infinispan.api.BasicCache;
// import org.infinispan.DecoratedCache;
// import org.infinispan.context.Flag;

import com.jboss.datagrid.chunchun.model.PostKey;
import com.jboss.datagrid.chunchun.model.User;
import com.jboss.datagrid.chunchun.util.PostKeyTimeComparator;
import com.jboss.datagrid.chunchun.util.UserPostKeyIterator;

/**
 * Handles post operations (sending posts, listing recent posts from all watched people,
 * listing own recent posts, ...)
 * 
 * @author Martin Gencur
 * 
 */
@Named
@SessionScoped
public class PostBean implements Serializable {

   private static final long serialVersionUID = -8914061755188086355L;

   private static final int INITIAL_SHOWED_POSTS = 10;
   private int showedPosts = INITIAL_SHOWED_POSTS;
   private static final int INCREASE_SHOWED_BY = 10; //increase showedPosts by

   private String message;

   LinkedList<DisplayPost> recentPosts = new LinkedList<DisplayPost>();
   transient private HashMap<PostKey, DisplayPost> recentPostsCache = new HashMap<PostKey, DisplayPost>();

   @Inject
   private Instance<Authenticator> auth;

   @Inject
   private UserBean userBean;

   @Inject
   private CacheContainerProvider provider;

   @Inject
   private UserTransaction utx;

   private Logger log = Logger.getLogger(this.getClass().getName());

   public String sendPost() {
      Post t = new Post(auth.get().getUsername(), message);
      try {
         utx.begin();
         User u = auth.get().getUser();
         getPostCache().put(t.getKey(), t);
         u.getPosts().add(t.getKey());
         getUserCache().replace(auth.get().getUsername(), u);
         utx.commit();
      } catch (Exception e) {
         if (utx != null) {
            try {
               utx.rollback();
            } catch (Exception e1) {
            }
         }
      }
      return null;
   }

   public void deletePost(DisplayPost post) {
      PostKey key;
      try {
         utx.begin();
         User u = auth.get().getUser();
         key = new PostKey(u.getUsername(), post.getTimeOfPost());
         getPostCache().remove(key); // TODO add flags new DecoratedCache(getPostCache(), Flag.SKIP_REMOTE_LOOKUP, Flag.SKIP_CACHE_LOAD);
         u.getPosts().remove(key);
         getUserCache().replace(auth.get().getUsername(), u);
         utx.commit();
      } catch (Exception e) {
         if (utx != null) {
            try {
               utx.rollback();
            } catch (Exception e1) {
               log.log(Level.SEVERE,"failed to rollback failed transaction to remove message", e1);
            }
         }
         throw new RuntimeException("failed to remove message: " + auth.get().getUsername() + " " + post.getTimeOfPost(), e);
      }
   }

   public List<DisplayPost> getRecentPosts() {
      if (recentPosts.size() < showedPosts) {
          reloadPosts(showedPosts);
      }
      return recentPosts;
   }

   /*
    * Reload content of recentPosts list
    * 
    */
   private void reloadPosts(int limit) {
      NavigableSet<PostKey> candidatePosts = new TreeSet<PostKey>(PostKeyTimeComparator.getInstance());
      HashMap<String, User> following = new HashMap<String, User>();
      // get a descending post iterator for each user
      Set<Iterator<PostKey>> followingPostsIterators = new HashSet<Iterator<PostKey>>();
      for (String username: auth.get().getUser().getWatching()) {
         User user = (User) getUserCache().get(username);
         following.put(username, user);
         followingPostsIterators.add(new UserPostKeyIterator(user, provider));
      }
      // avoid NoSuchElementException in next loop condition
      candidatePosts.add(new PostKey("dummy non existing username",0));
      // check each user for recent enough post /that fit into limit of posts/, lookup in one day long intervals
      long minAge = System.currentTimeMillis();
      do {
         minAge = minAge - (1000 * 60 * 60 * 24);
         Iterator<Iterator<PostKey>> followingPostsIteratorsIterator = followingPostsIterators.iterator();
         while (followingPostsIteratorsIterator.hasNext()) {
            Iterator<PostKey> postIterator = followingPostsIteratorsIterator.next();
            PostKey postKey;
            boolean hasMorePosts;
            while ( (hasMorePosts = postIterator.hasNext()) && ( !candidatePosts.first().getOwner().equals(postKey = postIterator.next() ) || candidatePosts.size() < limit) ) {
               if (candidatePosts.size() < limit) {
                  candidatePosts.add(postKey);
               } else if (postKey.getTimeOfPost() > candidatePosts.first().getTimeOfPost()) {
                  candidatePosts.pollFirst();
                  candidatePosts.add(postKey);
               } else {
                  break;
               }
               if (postKey.getTimeOfPost() < minAge) break;
            }
            if (!hasMorePosts) followingPostsIteratorsIterator.remove();
         }
      } while (candidatePosts.size() < limit && followingPostsIterators.size() > 0);
      followingPostsIterators = null; // lets be memory friendly
      // remove artificial first element in the case there are not enough real posts to fill limit
      if (candidatePosts.first().getTimeOfPost() == 0) candidatePosts.pollFirst();
      // we have the postKey of all possible posts within the limit, handle them
      HashMap<PostKey, DisplayPost> newPostsCache = new HashMap<PostKey, DisplayPost>();
      Iterator<PostKey> postsToDisplayIterator = candidatePosts.descendingIterator();
      recentPosts.clear();
      while (postsToDisplayIterator.hasNext()) {
         PostKey postKey = postsToDisplayIterator.next();
         DisplayPost post;
         Post rawPost;
         if ((post = recentPostsCache.get(postKey)) != null) {
            recentPosts.add(post);
            newPostsCache.put(postKey, post);
         } else if ((rawPost = (Post) getPostCache().get(postKey)) != null) {
            post = new DisplayPost(following.get(postKey.getOwner()).getName(), postKey.getOwner(), rawPost.getMessage(), postKey.getTimeOfPost());
            recentPosts.add(post);
            newPostsCache.put(postKey, post);
         } else {
            // post might have been removed in the meanwhile so we just ignore
         }
      }
      recentPostsCache = newPostsCache;
   }

   public void morePosts() {
       showedPosts += INCREASE_SHOWED_BY;
   }

   public void setDisplayedPostsLimit(int limit) {
       showedPosts = limit;
   }
   
   public int getDisplayedPostsLimit() {
       return showedPosts;   
   }

   public List<DisplayPost> getMyPosts() {
      LinkedList<DisplayPost> myPosts = new LinkedList<DisplayPost>();
      List<PostKey> myPostKeys = auth.get().getUser().getPosts();
      for (PostKey key : myPostKeys) {
         Post t = (Post) getPostCache().get(key);
         if (t != null) {
            DisplayPost dispPost = new DisplayPost(auth.get().getUser().getName(), auth.get()
                  .getUser().getUsername(), t.getMessage(), t.getTimeOfPost());
            myPosts.addFirst(dispPost);
         }
      }
      return myPosts;
   }

   public List<DisplayPost> getWatchedUserPosts() {
      LinkedList<DisplayPost> userPosts = new LinkedList<DisplayPost>();
      List<PostKey> myPostKeys = userBean.getWatchedUser().getPosts();
      for (PostKey key : myPostKeys) {
         Post t = (Post) getPostCache().get(key);
         if (t != null) {
            DisplayPost dispPost = new DisplayPost(userBean.getWatchedUser().getName(), userBean
                  .getWatchedUser().getUsername(), t.getMessage(), t.getTimeOfPost());
            userPosts.addFirst(dispPost);
         }
      }
      return userPosts;
   }

   public String getMessage() {
      return message;
   }

   public void setMessage(String message) {
      this.message = message;
   }

   private BasicCache<String, Object> getUserCache() {
      return provider.getCacheContainer().getCache("userCache");
   }

   private BasicCache<PostKey, Object> getPostCache() {
      return provider.getCacheContainer().getCache("postCache");
   }

   public void resetRecentPosts() {
      recentPosts.clear();
      showedPosts = INITIAL_SHOWED_POSTS;
   }
}
