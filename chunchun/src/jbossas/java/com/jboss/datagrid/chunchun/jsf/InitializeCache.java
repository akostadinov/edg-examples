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
package com.jboss.datagrid.chunchun.jsf;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Calendar;
import java.util.Date;
import java.util.Random;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.faces.application.Application;
import javax.faces.event.AbortProcessingException;
import javax.faces.event.SystemEvent;
import javax.faces.event.SystemEventListener;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.transaction.UserTransaction;
import org.infinispan.api.BasicCache;
import com.jboss.datagrid.chunchun.model.Post;
import com.jboss.datagrid.chunchun.model.PostKey;
import com.jboss.datagrid.chunchun.model.User;
import com.jboss.datagrid.chunchun.session.CacheContainerProvider;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;

/**
 * A JSF listener used to populate the cache with users, posts and user watchers/watching
 * people.
 *
 * @author Martin Gencur
 *
 */
public class InitializeCache implements SystemEventListener {
   public  static final  String   VERSION                    = "1.0.15";

   private static final int       USER_COUNT                 = Integer.getInteger("chunchun.cache.init.users", 3000);
   private static final int       SEVEN_DAYS_IN_MILLISECONDS = 7 * 24 * 3600 * 1000;
   private static final int       USER_WATCHES_COUNT         = Integer.getInteger("chunchun.cache.init.watches",20);
   private static final int       USER_MUTUAL_WATCHES_PERCENT = Integer.getInteger("chunchun.cache.init.mutual.watches.percent",95);
   private static final int       POSTS                      = Integer.getInteger("chunchun.cache.init.posts",20);
   private Random                 randomNumber               = new Random();
   private Logger                 log                        = Logger.getLogger(this.getClass().getName());
   private CacheContainerProvider provider; // with JSF 2.2 this can be injected
   private UserTransaction        utx;

   @Override
   public void processEvent(SystemEvent event) throws AbortProcessingException {
      provider = getContextualInstance(getBeanManagerFromJNDI(), CacheContainerProvider.class);
      if (! Boolean.getBoolean("chunchun.cache.init.skip")) startup();
   }

   public void startup() {
      log.info("Initializing chunchun cache with " + USER_COUNT + " users, each with " + POSTS + " initial posts and " + USER_WATCHES_COUNT + " user watches, version: " + VERSION);

      BasicCache<String, Object> users = provider.getCacheContainer().getCache("userCache");
      BasicCache<PostKey, Object> posts = provider.getCacheContainer().getCache("postCache");
      BasicCache<String, Object> avatars = provider.getCacheContainer().getCache("avatarCache");

      // try to avoid re-initializing cache if it exists
      if (users.get("user1") != null) {
         log.info("chunchun cache non-empty, skipping initialization");
         return;
      }

      utx = getUserTransactionFromJNDI();

      try {
         // initialize avatars
         utx.begin();
         avatars.put("user1.jpg", loadImageFromFile("images" + File.separator + "user1.jpg"));
         avatars.put("nophoto.jpg", loadImageFromFile("images" + File.separator + "nophoto.jpg"));
         utx.commit();

         // create users
         for (int i = 1; i <= USER_COUNT; i++) {
            utx.begin();
            User u = null;
            // if non-jpeg image needs to be used, make sure to tune a4j:mediaOutput mimeType, removing mimeType prop tested to work on FF18 and eclipse
            u = new User("user" + i, "Name" + i, "Surname" + i, "tmpPasswd",
                  "Description of person " + i, i % 2 == 1 ? "user1.jpg" : "nophoto.jpg");

            String encryptedPass = hashPassword("pass" + i);
            u.setPassword(encryptedPass);

            // GENERATE POSTS FOR EACH USER
            TreeSet<Long> randomTimesSorted = new TreeSet<Long>();
            for (int j = 1; j <= POSTS; j++) {
                randomTimesSorted.add(getRandomTime());
            }

            for (int j = 1; j <= POSTS; j++) {
               long randomTime = randomTimesSorted.pollFirst();
               Post t = new Post(u.getUsername(), "Post number " + j + " for user "
                        + u.getName() + " at " + new Date(randomTime), randomTime);
               // store the post in a cache
               posts.put(t.getKey(), t);
               u.getPosts().add(t.getKey());
            }
            // store the user in a cache
            users.put(u.getUsername(), u);
            utx.commit();
         }

         // GENERATE RANDOM WATCHERS AND WATCHING FOR EACH USER
         // USER_MUTUAL_WATCHES_PERCENT is only a target but can end up higher or lower
         // to remove possibilities of fluctuations in number of watching, then set USER_MUTUAL_WATCHES_PERCENT to 0
         for (int i = 1; i <= USER_COUNT; i++) {
            int selfBalance; // balance mutual watches in one pass by magic number 
            if (USER_MUTUAL_WATCHES_PERCENT == 0)
               selfBalance = 0;
            else {
               final int maxSelfBalance = USER_WATCHES_COUNT * USER_MUTUAL_WATCHES_PERCENT / 3 / 100;
               final int groups = maxSelfBalance;
               selfBalance = (int) (maxSelfBalance - (long) i * (groups + 1) / (USER_COUNT + 1));
            }

            utx.begin();
            int nonMutualWatching = 0;
            User u = (User) users.get("user" + i);
            while (u.getWatching().size() < USER_WATCHES_COUNT - selfBalance) {
               int id = randomNumber.nextInt(USER_COUNT) + 1; // do not return 0
               User watching = (User) users.get("user" + id);

               if (  watching == null ||
                     u.getUsername().equals(watching.getUsername()) ||
                     u.getWatching().contains(watching.getUsername())
                     ) continue;

               if ((u.getWatching().size() + selfBalance - nonMutualWatching) * 100l < (long) USER_WATCHES_COUNT * USER_MUTUAL_WATCHES_PERCENT) {
                  if (watching.getWatching().size() >= USER_WATCHES_COUNT) {
                     nonMutualWatching++;
                  } else {
                     // mutual watcher
                     u.addFollower(watching.getUsername());
                     watching.addFollowing(u.getUsername());
                  }
               }
               u.addFollowing(watching.getUsername());
               watching.addFollower(u.getUsername());
               users.replace(watching.getUsername(),watching);
            }
            users.replace(u.getUsername(), u);
            utx.commit();
         }

         log.info("Initializing cache completed successfully");
      } catch (Exception e) {
         log.log(Level.SEVERE, "An exception occured while populating the datagrid! Rolling back the transaction. Aborting cache initialization.", e);
         if (utx != null) {
            try {
               utx.rollback();
            } catch (Exception e1) {
            	log.log(Level.SEVERE, "failed to rollback transaction transaction for cache init", e1);
            }
         }
      }
   }
   
   private byte[] loadImageFromFile(String fileName) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[1024];
      int length;
      InputStream image = null;
      try {
         image = this.getClass().getClassLoader().getResourceAsStream(fileName);
         while ((length = image.read(buffer)) != -1) out.write(buffer, 0, length);
         image.close();
         out.close();
         return out.toByteArray();
      } catch (IOException e) {
         throw new RuntimeException("Unable to load image from file " + fileName);
      }
   }

   private long getRandomTime() {
      // get random time at most 7 days old
      return Calendar.getInstance().getTimeInMillis()
               - randomNumber.nextInt(SEVEN_DAYS_IN_MILLISECONDS);
   }

   public static String hashPassword(String password) {
      String hashword = null;
      try {
         MessageDigest md5 = MessageDigest.getInstance("MD5");
         md5.update(password.getBytes());
         BigInteger hash = new BigInteger(1, md5.digest());
         hashword = hash.toString(16);
      } catch (NoSuchAlgorithmException nsae) {
         throw new RuntimeException("No MD5 algorithm found for password encryption!");
      }
      return hashword;
   }

   public static int getUserCount() {
      return USER_COUNT;
   }
   
   public static int getUserWatchesCount() {
      return USER_WATCHES_COUNT;
   }

   public static int getUserMutualWatchesPercent() {
      return USER_MUTUAL_WATCHES_PERCENT;
   }

   public static int getPosts() {
      return POSTS;
   }

   private BeanManager getBeanManagerFromJNDI() {
      InitialContext context;
      Object result;
      try {
         context = new InitialContext();
         result = context.lookup("java:comp/BeanManager");
      } catch (NamingException e) {
         throw new RuntimeException("BeanManager could not be found in JNDI", e);
      }
      return (BeanManager) result;
   }

   private UserTransaction getUserTransactionFromJNDI() {
      InitialContext context;
      Object result;
      try {
         context = new InitialContext();
         result = context.lookup("java:comp/UserTransaction");
      } catch (NamingException ex) {
         throw new RuntimeException("UserTransaction could not be found in JNDI", ex);
      }
      return (UserTransaction) result;
   }

   @SuppressWarnings("unchecked")
   public <T> T getContextualInstance(final BeanManager manager, final Class<T> type) {
      T result = null;
      Bean<T> bean = (Bean<T>) manager.resolve(manager.getBeans(type));
      if (bean != null) {
         CreationalContext<T> context = manager.createCreationalContext(bean);
         if (context != null) {
            result = (T) manager.getReference(bean, type, context);
         }
      }
      return result;
   }

   @Override
   public boolean isListenerForSource(Object source) {
      return source instanceof Application;
   }
}
