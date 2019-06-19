/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */

package tech.beshu.ror.utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.reflections.ReflectionUtils;
import tech.beshu.ror.Constants;
import tech.beshu.ror.SecurityPermissionException;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.HashMap;
import java.util.Set;

/**
 * Created by sscarduzio on 24/03/2017.
 */
public class ReflecUtils {

  private static final HashMap<String, Method> methodsCache = new HashMap<>(128);
  private static final Logger logger = LogManager.getLogger(ReflecUtils.class);

  public static Object invokeMethodCached(Object o, Class c, String method) {
    String cacheKey = c.getName() + "#" + method;

    return AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
      try {
        Method m = methodsCache.get(cacheKey);
        if (m != null) {
          return m.invoke(o);
        }
        try {
          m = c.getDeclaredMethod(method);
        } catch (NoSuchMethodException nsme) {
          m = c.getMethod(method);
        }
        m.setAccessible(true);
        methodsCache.put(cacheKey, m);
        return m.invoke(o);
      } catch (Exception e) {
        e.printStackTrace();
        return null;
      }
    });
  }

  public static Object invokeMethod(Object o, Class c, String method) {
    String cacheKey = c.getName() + "#" + method;
    return AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
      try {
        Method m;
        try {
          m = c.getDeclaredMethod( method);
        } catch (NoSuchMethodException nsme) {
          m = c.getMethod(method);
        }
        m.setAccessible(true);
        methodsCache.put(cacheKey, m);
        return m.invoke(o);
      } catch (Exception e) {
        e.printStackTrace();
        return null;
      }
    });
  }

  public static String[] extractStringArrayFromPrivateMethod(String methodName, Object o) {
    final String[][] result = {new String[]{}};
    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
      if (o == null) {
        throw new ReadonlyRestIllegalStateException("cannot extract field from null!");
      }
      Class<?> clazz = o.getClass();
      while (!clazz.equals(Object.class)) {

        try {
          Method m = exploreClassMethods(clazz, methodName, String[].class);
          if (m != null) {
            result[0] = (String[]) m.invoke(o);
            return null;
          }

          m = exploreClassMethods(clazz, methodName, String.class);
          if (m != null) {
            result[0] = new String[]{(String) m.invoke(o)};
            return null;
          }
        } catch (SecurityException e) {
          logger.error("Can't get indices for request because of wrong security configuration " + o.getClass());
          throw new SecurityPermissionException(
            "Insufficient permissions to extract field " + methodName + ". Abort! Cause: " + e.getMessage(), e);
        } catch (Exception e) {
          logger.debug("Cannot to discover field " + methodName + " associated to this request: " + o.getClass());
        }
        clazz = clazz.getSuperclass();
      }
      return null;
    });
    return result[0];
  }

  private static Method exploreClassMethods(Class<?> c, String methodName, Class<?> returnClass) {
    // Explore methods without the performance cost of throwing field not found exceptions..
    // The native implementation is O(n), so we do likewise, but without the exception object creation.
    String cacheKey = c.getName() + methodName + returnClass.getName();
    Method theMethod = methodsCache.get(cacheKey);
    if (theMethod != null) {
      return theMethod;
    }
    for (Method m : c.getDeclaredMethods()) {
      if (methodName.equals(m.getName()) && m.getReturnType().equals(returnClass)) {
        if (methodsCache.size() > Constants.CACHE_WATERMARK) {
          new Exception("Method cache has exceeded the watermark of " + Constants.CACHE_WATERMARK +
                          " keys, currently at " + methodsCache.size()).printStackTrace();
        }
        m.setAccessible(true);
        methodsCache.put(cacheKey, m);
        return m;
      }
    }
    return null;
  }

  public static boolean setIndices(Object o, Set<String> fieldNames, Set<String> newIndices) {
    if(newIndices.isEmpty()) return false;
    final boolean[] res = {false};
    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
      @SuppressWarnings("unchecked")
      Set<Field> indexFields = ReflectionUtils.getAllFields(
        o.getClass(),
        (Field field) -> field != null && fieldNames.contains(field.getName()) &&
          (field.getType().equals(String.class) || field.getType().equals(String[].class))
      );
      String firstIndex = newIndices.iterator().next();
      for (Field f : indexFields) {
        f.setAccessible(true);
        try {
          if (f.getType().equals(String[].class)) {
            f.set(o, newIndices.toArray(new String[]{}));
          }
          else {
            f.set(o, firstIndex);
          }
          res[0] = true;
        } catch (IllegalAccessException | IllegalArgumentException e) {
          logger.error("could not find index or indices field to replace: " +
                         e.getMessage() + " and then " + e.getMessage());
        }
      }
      return null;
    });
    return res[0];
  }
}
