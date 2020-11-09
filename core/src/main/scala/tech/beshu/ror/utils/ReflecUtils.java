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
import tech.beshu.ror.Constants;
import tech.beshu.ror.exceptions.SecurityPermissionException;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;

import static org.reflections.ReflectionUtils.getAllFields;
import static org.reflections.ReflectionUtils.getAllMethods;
import static org.reflections.ReflectionUtils.withModifier;
import static org.reflections.ReflectionUtils.withName;
import static org.reflections.ReflectionUtils.withParametersCount;

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

  public static Object getField(Object o, Class c, String field) {
    return AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
      try {
        Field f;
        try {
          f = c.getDeclaredField(field);
        } catch (NoSuchFieldException nsfe) {
          f = c.getField(field);
        }
        f.setAccessible(true);
        return f.get(o);

      } catch (Exception e) {
        e.printStackTrace();
        return null;
      }
    });
  }


  public static Object setField(Object o, Class c, String field, Object value) {
    return AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
      try {
        Field f;
        try {
          f = c.getDeclaredField(field);
        } catch (NoSuchFieldException nsfe) {
          f = c.getField(field);
        }
        f.setAccessible(true);
        f.set(o, value);
        return o;
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

  public static String[] extractStringArrayFromPrivateMethod(String methodName, Object o) {
    return AccessController.doPrivileged((PrivilegedAction<String[]>) () -> {
      if (o == null) {
        throw new IllegalStateException("cannot extract field from null!");
      }
      Class<?> clazz = o.getClass();
      while (!clazz.equals(Object.class)) {

        try {
          Method m = exploreClassMethods(clazz, methodName, String[].class);
          if (m != null) {
            Object result = m.invoke(o);
            return result != null
                ? Arrays
                .stream((String[]) result)
                .filter(Objects::nonNull)
                .toArray(String[]::new)
                : new String[0];
          }

          m = exploreClassMethods(clazz, methodName, String.class);
          if (m != null) {
            Object result = m.invoke(o);
            return result != null ? new String[]{(String) result} : new String[0];
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
      return new String[0];
    });
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
    if (newIndices.isEmpty()) return false;
    final boolean[] res = {false};
    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
      @SuppressWarnings("unchecked")
      Set<Field> indexFields = getAllFields(
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
          } else {
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

  public static Method getMethodOf(Class<?> aClass, int modifier, String name, int paramsCount) {
    Set<Method> allMethods = getAllMethods(
        aClass,
        withModifier(modifier),
        withName(name),
        withParametersCount(paramsCount)
    );
    if (allMethods.size() == 0) {
      throw new IllegalArgumentException("Cannot find method '" + name + "' of class " + aClass.getSimpleName());
    } else if (allMethods.size() == 1) {
      return allMethods.toArray(new Method[0])[0];
    } else {
      throw new IllegalArgumentException("More than one method with name '" + name + "' of class " + aClass.getSimpleName());
    }
  }

  public static Field getFieldOf(Class<?> aClass, int modifier, String name) {
    Set<Field> allFields = getAllFields(
        aClass,
        withModifier(modifier),
        withName(name)
    );
    if (allFields.size() == 0) {
      throw new IllegalArgumentException("Cannot find field with name '" + name + "' of class " + aClass.getSimpleName());
    } else if (allFields.size() == 1) {
      return allFields.toArray(new Field[0])[0];
    } else {
      throw new IllegalArgumentException("More than one field with name '" + name + "' of class " + aClass.getSimpleName());
    }
  }
}
