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

import java.util.Comparator;
import java.util.List;

import com.jboss.datagrid.chunchun.model.PostKey;

/**
 * @author Aleksandar Kostadinov
 *
 */
public class PostKeyTimeComparator implements Comparator<PostKey> {

   private static final PostKeyTimeComparator singleton = new PostKeyTimeComparator();

   private PostKeyTimeComparator() {
      super();
   }

   @Override
   public int compare(PostKey arg0, PostKey arg1) {
      if (arg0.getTimeOfPost() == arg1.getTimeOfPost()) {
         return 0;
      } else if (arg0.getTimeOfPost() < arg1.getTimeOfPost()) {
         return -1;
      } else {
         return 1;
      }
   }

   public static PostKeyTimeComparator getInstance() {
      return singleton;
   }

   // innefficient, use only on small lists
   public static void addToListSorted(List<PostKey> list, PostKey postKey) {
      for (int i=0; i<list.size(); i++ ) {
         if(list.get(i).getTimeOfPost() > postKey.getTimeOfPost()) {
            list.add(i, postKey);
            return;
         }
      }
      list.add(postKey);
   }
}