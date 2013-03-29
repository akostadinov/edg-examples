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

import java.io.OutputStream;
import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.PostConstruct;
import javax.enterprise.context.SessionScoped;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.inject.Named;
import javax.transaction.UserTransaction;
import org.infinispan.api.BasicCache;
import com.jboss.datagrid.chunchun.model.User;

/**
 * Handles operations with users (retrieving users from a cache, getting 
 * watched/watching users, ...)
 * 
 * @author Martin Gencur
 * 
 */
@Named
@SessionScoped
public class UserBean implements Serializable {

   private static final long serialVersionUID = -5419061180849357611L;

   @Inject
   private Instance<Authenticator> auth;

   @Inject
   private PostBean postBean;

   private User watchedUser;

   @Inject
   private CacheContainerProvider provider;

   private Logger log = Logger.getLogger(this.getClass().getName());

   @PostConstruct
   public void initialize() {
      watchedUser = auth.get().getUser(); 
   }

   @Inject
   private UserTransaction utx;

   // TODO: make this method return avatar by reference instead of username, store avatar reference in DisplayPost
   public void showUserImage(OutputStream out, Object data) {
      try {
         User u = (User) getUserCache().get((String) data);
         out.write(getAvatarCache().get(u.getAvatar()));
      } catch (Exception e) {
         throw new RuntimeException("Unable to load data for image", e);
      }
   }
   
   public List<User> getWatching() {
      List<User> returnWatching = new LinkedList<User>();
      List<String> watching = watchedUser.getWatching();
      for (String username : watching) {
         if (username != null) {
            User u = (User) getUserCache().get(username);
            returnWatching.add(u);
         }
      }
      return returnWatching;
   }

   public List<User> getWatchers() {
      List<User> returnWatchers = new LinkedList<User>();
      List<String> watchers = watchedUser.getWatchers();
      for (String username : watchers) {
         User u = (User) getUserCache().get(username);
         returnWatchers.add(u);
      }
      return returnWatchers;
   }

   public String showUser(User user) {
      this.watchedUser = user;
      return "userdetails";
   }

   public String showUser(DisplayPost post) {
      this.watchedUser = (User) getUserCache().get(post.getOwnerUsername());
      return "userdetails";
   }

   private BasicCache<String, Object> getUserCache() {
      return provider.getCacheContainer().getCache("userCache");
   }

   private BasicCache<String, byte[]> getAvatarCache() {
      return provider.getCacheContainer().getCache("avatarCache");
   }

   public User getWatchedUser() {
      return watchedUser;
   }

   public void setWatchedUser(User u) {
      this.watchedUser = u;
   }

   public String goHome() {
      this.watchedUser = auth.get().getUser();
      return "home";
   }

   public boolean isWatchedByMe(User u) {
      List<String> watching = auth.get().getUser().getWatching();
      return watching.contains(u.getUsername());
   }

   public boolean isMe(User u) {
      return auth.get().getUser().equals(u);
   }

   public String watchUser(User user) {
      User me = this.auth.get().getUser();
      try {
         utx.begin();
         me.getWatching().add(user.getUsername());
         User watchedUser = (User) getUserCache().get(user.getUsername());
         watchedUser.getWatchers().add(me.getUsername());
         getUserCache().replace(watchedUser.getUsername(), watchedUser); //to let Infinispan know the entry has changed -> replicate
         getUserCache().replace(me.getUsername(), me); //to let Infinispan know the entry has changed -> replicate
         utx.commit();

         postBean.resetRecentPosts();
      } catch (Exception e) {
         if (utx != null) {
            try {
               utx.rollback();
               log.log(Level.SEVERE, "failed to rollback transaction for watching user " + user.getUsername(), e);
            } catch (Exception e1) {
            }
         }
         throw new RuntimeException("failed to start watching user " + user.getUsername());
      }

      return null;
   }
   
   public String stopWatchingUser(User user) {
      User me = this.auth.get().getUser();
      try {
         utx.begin();
         me.getWatching().remove(user.getUsername());
         User watchedUser = (User) getUserCache().get(user.getUsername());
         watchedUser.getWatchers().remove(me.getUsername());
         getUserCache().replace(watchedUser.getUsername(), watchedUser);
         getUserCache().replace(me.getUsername(), me);
         utx.commit();

         postBean.resetRecentPosts();
      } catch (Exception e) {
         if (utx != null) {
            try {
               utx.rollback();
            } catch (Exception e1) {
               log.log(Level.SEVERE, "failed to rollback stop-watching transaction for user" + user.getUsername(), e);
            }
         }
         throw new RuntimeException("failed to stop watching user " + user.getUsername());
      }

      return null;
   }

}
