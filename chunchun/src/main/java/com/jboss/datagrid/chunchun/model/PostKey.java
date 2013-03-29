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
package com.jboss.datagrid.chunchun.model;

import java.io.Serializable;

/**
 * Identifies a post.
 * 
 * @author Martin Gencur
 * 
 */

public class PostKey implements Serializable {

   private static final long serialVersionUID = -7624874290312596494L;

   private String owner;
   
   private long timeOfPost;

   public PostKey(String owner, long timeOfPost) {
      this.owner = owner;
      this.timeOfPost = timeOfPost;
   }

   public String getOwner() {
      return owner;
   }

   public void setOwner(String username) {
      this.owner = username;
   }

   public long getTimeOfPost() {
      return timeOfPost;
   }

   public void setTimeOfPost(long timeOfPost) {
      this.timeOfPost = timeOfPost;
   }
   
   public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result + ((owner == null) ? 0 : owner.hashCode());
      result = prime * result + (int) (timeOfPost ^ (timeOfPost >>> 32));
      return result;
   }

   public boolean equals(Object obj) {
      if (this == obj)
         return true;
      if (obj == null || getClass() != obj.getClass())
         return false;
      PostKey other = (PostKey) obj;
      if (owner == null) {
         if (other.owner != null)
            return false;
      } else if (!owner.equals(other.owner))
         return false;
      if (timeOfPost != other.timeOfPost)
         return false;
      return true;
   }

   /*
    * Converts object to string concatenating owner and timeOfPost with a dash.
    * Useful for string mapping when jdbc cache store is used. Optimized for
    * putting into a b-tree index I imagine.
    *
    */
   public String toDBKeyString() {
      // funky method to make up a random sorting index to help btrees but benefits are negligible if any
      // String timestamp = String.format("%016X", Long.reverse(timeOfPost));
      // return timestamp.substring(0, 4) + owner + timestamp.substring(4);

      // mostly incrementing key
      return Long.toString(timeOfPost, 16) + ":" + owner;
   }

   public static PostKey fromDBKeyString(String val) {
      // restore from the funky method in toDBKeyString()
      // long timestamp = Long.parseLong(val.substring(val.length() - 12), 16) |
      //                      Long.parseLong(val.substring(0, 4), 16)<<48;
      // return new PostKey(val.substring(4, val.length() - 12), Long.reverse(timestamp));

      int indexOfSeparator = val.indexOf(":");
      return new PostKey(val.substring(indexOfSeparator + 1), Long.parseLong(val.substring(0, indexOfSeparator), 16));
   }
}
