package org.elasticsearch.plugin.readonlyrest.utils;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;

import java.lang.reflect.Field;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by sscarduzio on 24/03/2017.
 */
public class ReflectionUtils {

  private static Field exploreClass(Class<?> c, String fieldName) throws NoSuchFieldException {
    // Explorative section..
    for (Field f : c.getDeclaredFields()) {
      if (fieldName.equals(f.getName())) {
        f.setAccessible(true);
        return f;
      }
    }
    return null;
  }

  public static List<Throwable> fieldChanger(final Class<?> clazz, String fieldName, Logger logger, RequestContext rc, CheckedFunction<Field, Void> change) {
    final List<Throwable> errors = new ArrayList<>();

    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
      Class<?> theClass = clazz;
      final Class<?> originalClass = theClass;


      while (!theClass.equals(Object.class)) {
        try {
          logger.debug(theClass.getSimpleName() + " < " + theClass.getSuperclass().getSimpleName() + rc);
          Field f = exploreClass(theClass, fieldName);
          if (f != null) {
            logger.debug("found field " + fieldName + " in class " + theClass.getSimpleName());
            change.apply(f);
            errors.clear();
            return null;
          }
          else {
            theClass = theClass.getSuperclass();
            throw new NoSuchFieldException("Cannot find field " + fieldName + "in class" + theClass.getSimpleName());
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
          Field f = exploreClass(theClass, fieldName);
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
