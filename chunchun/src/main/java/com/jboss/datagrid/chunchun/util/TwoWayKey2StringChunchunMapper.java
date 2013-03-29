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

import org.infinispan.loaders.keymappers.TwoWayKey2StringMapper;
import org.infinispan.util.Base64;
import org.infinispan.util.ByteArrayKey;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import com.jboss.datagrid.chunchun.model.PostKey;

/**
 * TwoWayKey2StringMapper supporting PostKey to allow use of stringKeyedJdbcStore
 * this class copies DefaultTwoWayKey2StringMapper and adds support for PostKey
 *
 * @author Aleksandar Kostadinov
 *
 */
public class TwoWayKey2StringChunchunMapper implements TwoWayKey2StringMapper {

   public static final char NON_STRING_PREFIX = '\uFEFF';
   public static final char SHORT_IDENTIFIER = '1';
   public static final char BYTE_IDENTIFIER = '2';
   public static final char LONG_IDENTIFIER = '3';
   public static final char INTEGER_IDENTIFIER = '4';
   public static final char DOUBLE_IDENTIFIER = '5';
   public static final char FLOAT_IDENTIFIER = '6';
   public static final char BOOLEAN_IDENTIFIER = '7';
   public static final char BYTEARRAYKEY_IDENTIFIER = '8';
   public static final char POSTKEY_IDENTIFIER = 'p';

   private static final Log log = LogFactory.getLog(TwoWayKey2StringChunchunMapper.class);

   public TwoWayKey2StringChunchunMapper() {
      super();
   }

   @Override
   public String getStringMapping(Object key) {
      char identifier;
      if (key.getClass().equals(String.class)) {
         return key.toString();
      } else if (key.getClass().equals(PostKey.class)) {
         return generateString(POSTKEY_IDENTIFIER, ((PostKey) key).toDBKeyString());
      } else if (key.getClass().equals(Short.class)) {
         identifier = SHORT_IDENTIFIER;
      } else if (key.getClass().equals(Byte.class)) {
         identifier = BYTE_IDENTIFIER;
      } else if (key.getClass().equals(Long.class)) {
         identifier = LONG_IDENTIFIER;
      } else if (key.getClass().equals(Integer.class)) {
         identifier = INTEGER_IDENTIFIER;
      } else if (key.getClass().equals(Double.class)) {
         identifier = DOUBLE_IDENTIFIER;
      } else if (key.getClass().equals(Float.class)) {
         identifier = FLOAT_IDENTIFIER;
      } else if (key.getClass().equals(Boolean.class)) {
         identifier = BOOLEAN_IDENTIFIER;
      } else if (key.getClass().equals(ByteArrayKey.class)) {
         return generateString(BYTEARRAYKEY_IDENTIFIER, Base64.encodeBytes(((ByteArrayKey)key).getData()));
      } else {
         throw new IllegalArgumentException("Unsupported key type: " + key.getClass().getName());
      }
      return generateString(identifier, key.toString());
   }

   @Override
   public Object getKeyMapping(String key) {
      log.tracef("Get mapping for key: %s", key);
      if (key.length() > 0 && key.charAt(0) == NON_STRING_PREFIX) {
         char type = key.charAt(1);
         String value = key.substring(2);
         switch (type) {
            case POSTKEY_IDENTIFIER:
               return PostKey.fromDBKeyString(value);
            case SHORT_IDENTIFIER:
               return Short.parseShort(value);
            case BYTE_IDENTIFIER:
               return Byte.parseByte(value);
            case LONG_IDENTIFIER:
               return Long.parseLong(value);
            case INTEGER_IDENTIFIER:
               return Integer.parseInt(value);
            case DOUBLE_IDENTIFIER:
               return Double.parseDouble(value);
            case FLOAT_IDENTIFIER:
               return Float.parseFloat(value);
            case BOOLEAN_IDENTIFIER:
               return Boolean.parseBoolean(value);
            case BYTEARRAYKEY_IDENTIFIER:
               return new ByteArrayKey(Base64.decode(value));
            default:
               throw new IllegalArgumentException("Unsupported type code: " + type);
         }
      } else {
         return key;
      }
   }

   @Override
   public boolean isSupportedType(Class<?> keyType) {
      return keyType == PostKey.class || isPrimitive(keyType);
   }

   private String generateString(char identifier, String s) {
      return String.valueOf(NON_STRING_PREFIX) + String.valueOf(identifier) + s;
   }

   static boolean isPrimitive(Class<?> key) {
      return key == String.class || key == Short.class || key == Byte.class || key == Long.class || key == Integer.class || key == Double.class || key == Float.class || key == Boolean.class || key == ByteArrayKey.class;
   }
}