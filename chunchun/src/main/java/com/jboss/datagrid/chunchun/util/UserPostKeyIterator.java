/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013, Red Hat Middleware LLC, and individual contributors
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
package com.jboss.datagrid.chunchun.util;

import java.util.Iterator;
import java.util.List;
import org.infinispan.api.BasicCache;
import com.jboss.datagrid.chunchun.model.PostKey;
import com.jboss.datagrid.chunchun.model.User;
import com.jboss.datagrid.chunchun.session.CacheContainerProvider;

/**
 * @author Aleksandar Kostadinov
 *
 * This iterator would iterate over {@link User}'s recent posts in reverse order.
 * If in the future older and recent posts are stored in separate locations, this
 * this can make it transparent to the iterating user. This is the reason we need
 * a reference to the CacheContainerProvider.
 * No guarantees are made that {@link Post} will not be deleted in the mean time.
 */
public class UserPostKeyIterator implements Iterator<PostKey> {

   // private User user;
   // private CacheContainerProvider provider;

   private List<PostKey> recentPosts;
   private int recentPostsIndex;

   // private Logger log = Logger.getLogger(this.getClass().getName());

   public UserPostKeyIterator(String user, CacheContainerProvider provider) {
      this((User) getUserCache(provider).get(user), provider);
   }

   public UserPostKeyIterator(User user, CacheContainerProvider provider) {
      // this.provider = provider;
      // this.utx = utx;
      // this.user = user;
      this.recentPosts = user.getPosts();
      this.recentPostsIndex = user.getPosts().size() - 1;
   }

   @Override
   public boolean hasNext() {
      if (recentPostsIndex >= 0) return true;
      return false;
   }

   @Override
   public PostKey next() {
      return recentPosts.get(recentPostsIndex--);
   }

   @Override
   public void remove() {
      throw new UnsupportedOperationException("removing user posts not supported through iterator");      
   }

   private static BasicCache<String, Object> getUserCache(CacheContainerProvider provider) {
      return provider.getCacheContainer().getCache("userCache");
   }
}