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

package org.elasticsearch.plugin.readonlyrest.utils;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.SecurityPermissionException;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Set;

import static org.reflections.ReflectionUtils.getAllFields;

/**
 * Created by sscarduzio on 24/03/2017.
 */
public class ReflecUtils {

  public static String[] extractStringArrayFromPrivateMethod(String methodName, Object o, ESContext context) {
    final String[][] result = {new String[]{}};
    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
      if (o == null) {
        throw context.rorException("cannot extract field from null!");
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
          context.logger(ReflecUtils.class)
              .error("Can't get indices for request because of wrong security configuration " + o.getClass());
          throw new SecurityPermissionException(
              "Insufficient permissions to extract field " + methodName + ". Abort! Cause: " + e.getMessage(), e);
        } catch (Exception e) {
          context.logger(ReflecUtils.class)
              .debug("Cannot to discover field " + methodName + " associated to this request: " + o.getClass());
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
    for (Method m : c.getDeclaredMethods()) {
      m.setAccessible(true);
      if (methodName.equals(m.getName()) && m.getReturnType().equals(returnClass)) {
        return m;
      }
    }
    return null;
  }

  private static Field exploreClassFields(Class<?> c, String fieldName) throws NoSuchFieldException {
    // Explore fields without the performance cost of throwing field not found exceptions..
    // The native implementation is O(n), so we do likewise, but without the exception object creation.
    for (Field f : c.getDeclaredFields()) {
      if (fieldName.equals(f.getName())) {
        f.setAccessible(true);
        return f;
      }
    }
    return null;
  }

  public static boolean setIndices(Object o, Set<String> fieldNames, Set<String> newIndices, Logger logger) {

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


  @FunctionalInterface
  public interface CheckedFunction<T, R> {
    R apply(T t) throws IllegalAccessException;
  }

  static class SetFieldException extends Exception {
    SetFieldException(Class<?> c, String id, String fieldName, Throwable e) {
      super(" Could not set " + fieldName + " to class " + c.getSimpleName() +
          "for req id: " + id + " because: "
          + e.getClass().getSimpleName() + " : " + e.getMessage() +
          (e.getCause() != null ? " caused by: " + e.getCause().getClass().getSimpleName() + " : " + e.getCause().getMessage() : ""));
    }
  }
}
