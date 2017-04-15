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
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.plugin.readonlyrest.SecurityPermissionException;
import org.elasticsearch.plugin.readonlyrest.acl.requestcontext.RequestContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by sscarduzio on 24/03/2017.
 */
public class ReflectionUtils {

  public static String[] extractStringArrayFromPrivateMethod(String methodName, Object o, Logger logger) {
    final String[][] result = {new String[]{}};
    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
      if (o == null) {
        throw new ElasticsearchException("cannot extract field from null!");
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

  public static List<Throwable> fieldChanger(final Class<?> clazz, String fieldName, Logger logger, RequestContext rc,
      CheckedFunction<Field, Void> change) {

    final List<Throwable> errors = new ArrayList<>();

    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
      Class<?> theClass = clazz;
      final Class<?> originalClass = theClass;


      while (!theClass.equals(Object.class)) {
        try {
          logger.debug(theClass.getSimpleName() + " < " + theClass.getSuperclass().getSimpleName() + rc);
          Field f = exploreClassFields(theClass, fieldName);
          if (f != null) {
            logger.debug("found field " + fieldName + " in class " + theClass.getSimpleName());
            change.apply(f);
            errors.clear();
            return null;
          }
          else {
            theClass = theClass.getSuperclass();
            throw new NoSuchFieldException("Cannot find field " + fieldName + " in class" + theClass.getSimpleName());
          }
        } catch (NoSuchFieldException | IllegalAccessException | IllegalArgumentException e) {
          errors.add(new SetFieldException(theClass, fieldName, rc.getId(), e));
        }
      }

      logger.debug("moving on with interfaces...");
      int interfacesLen = originalClass.getInterfaces().length;
      int interfacesNum = 0;

      while (interfacesNum < interfacesLen) {
        theClass = originalClass.getInterfaces()[interfacesNum];
        try {
          logger.debug(theClass.getSimpleName() + " < " + rc);
          Field f = exploreClassFields(theClass, fieldName);
          if (f != null) {
            logger.debug("found field " + fieldName + " in interface " + theClass.getSimpleName());
            change.apply(f);
            errors.clear();
            return null;
          }
        } catch (NoSuchFieldException | IllegalAccessException | IllegalArgumentException e) {
          errors.add(new SetFieldException(theClass, fieldName, rc.getId(), e));
        }
        interfacesNum++;
      }
      return null;
    });
    return errors;
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
