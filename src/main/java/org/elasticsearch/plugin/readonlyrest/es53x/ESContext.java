package org.elasticsearch.plugin.readonlyrest.es53x;

import org.apache.logging.log4j.Logger;

public interface ESContext {
  Logger logger(Class<?> clazz);
}
