package org.elasticsearch.plugin.readonlyrest;

import org.apache.logging.log4j.Logger;

public interface ESContext {
  Logger logger(Class<?> clazz);
  RuntimeException rorException(String message);
}
