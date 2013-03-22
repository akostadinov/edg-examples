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
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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

   private static final int INITIAL_POSTS_LIMIT = 30;
   private int loadedPosts = INITIAL_POSTS_LIMIT;
   // private static final int INCREASE_LOADED_BY = 50; //increase loadedPosts by
   private static final int INITIAL_SHOWED_POSTS = 10;
   private int showedPosts = INITIAL_SHOWED_POSTS;
   private static final int INCREASE_SHOWED_BY = 10; //increase showedPosts by
   
   private static final long MINUTE = 60 * 1000;
   private static final long TEN_MINUTES = 10 * MINUTE;
   private static final long THIRTY_MINUTES = 30 * MINUTE;
   private static final long HOUR = 60 * MINUTE;
   private static final long TWELVE_HOURS = 12 * HOUR;
   private static final long DAY = 24 * HOUR;
   private static final long THREE_DAYS = DAY * 3;
   private static final long SEVEN_DAYS = DAY * 7;
   
   private static final long LOW_WATCH_LIMIT = 50;
   private static final long MEDIUM_WATCH_LIMIT = 300;
   private static final long HIGH_WATCH_LIMIT = 1000;

   //shorten time steps with increasing number of people that I'm watching
   private static long agesByNumOfWatched[][] = {
            { DAY, THREE_DAYS, SEVEN_DAYS }, 
            { HOUR, TWELVE_HOURS, DAY, THREE_DAYS, SEVEN_DAYS },
            { MINUTE, THIRTY_MINUTES, HOUR, DAY, SEVEN_DAYS },
            { MINUTE, TEN_MINUTES, THIRTY_MINUTES, TWELVE_HOURS, SEVEN_DAYS } };

   private String message;

   LinkedList<DisplayPost> recentPosts = new LinkedList<DisplayPost>();

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
      if (recentPosts.size() <= INITIAL_POSTS_LIMIT) {
          reloadPosts(INITIAL_POSTS_LIMIT);
      }
      if (showedPosts > loadedPosts) {
          loadedPosts = showedPosts;
          reloadPosts(loadedPosts);
      }
      return recentPosts.subList(0, showedPosts);
   }
   
   /*
    * Reload content of recentPosts list
    */
   private void reloadPosts(int limit) {
       long now = System.currentTimeMillis();
       List<String> following = auth.get().getUser().getWatching();
       long[] ages = chooseRecentPostsStrategy(following.size());
       //make sure we have an empty list before reloading, otherwise entries will be contained more than once
       if (recentPosts.size() > 0) {
          recentPosts.clear();
       }
       // add initial entry (oldest possible one)
       recentPosts.add(new DisplayPost());
       // first check only posts newer than 1 hour, then increase maxAge
       for (int maxAge = 0; maxAge != ages.length; maxAge++) {
          if (recentPosts.size() >= limit) {
             break;
          }
          // get all people that I'm following
          for (String username : following) {
             User u = (User) getUserCache().get(username);
             CopyOnWriteArrayList<PostKey> postKeys = (CopyOnWriteArrayList<PostKey>) u.getPosts();
             LinkedList<PostKey> postKeysLinked = new LinkedList<PostKey>();
             postKeysLinked.addAll(postKeys);
             Iterator<PostKey> it = postKeysLinked.descendingIterator();
             // go from newest to oldest post
             while (it.hasNext()) {
                PostKey key = it.next();
                //check only desired sector in the past
                if (maxAge > 0 && key.getTimeOfPost() >= (now - ages[maxAge - 1])) {
                   // if we checked this post in the previous sector, move on
                   continue;
                } else if (key.getTimeOfPost() < (now - ages[maxAge])) {
                   // if the post is older than what belongs to this sector, move on
                   break;
                }
                int position = 0;
                //possibly add the post to newest posts
                for (DisplayPost recentPost : recentPosts) {
                   if (key.getTimeOfPost() > recentPost.getTimeOfPost()) {
                      Post t = (Post) getPostCache().get(key);
                      if (t != null) {
                         DisplayPost tw = new DisplayPost(u.getName(), u.getUsername(), t.getMessage(), t.getTimeOfPost());
                         recentPosts.add(position, tw);
                         if (recentPosts.size() > limit) {
                            recentPosts.removeLast();
                         }
                      }
                      break;
                   }
                   position++;
                }
             }
          }
       }
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
   
   private long[] chooseRecentPostsStrategy(int size) {
      if (size < LOW_WATCH_LIMIT) {
         return agesByNumOfWatched[0];
      } else if (size < MEDIUM_WATCH_LIMIT) {
         return agesByNumOfWatched[1];
      } else if (size < HIGH_WATCH_LIMIT) {
         return agesByNumOfWatched[2];
      } else {
         return agesByNumOfWatched[3];
      }
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
      recentPosts = new LinkedList<DisplayPost>();
      showedPosts = INITIAL_SHOWED_POSTS;
      loadedPosts = INITIAL_POSTS_LIMIT;
   }
}
