package org.elasticsearch.plugin.readonlyrest.es53x;


import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.logging.Loggers;

public class ESContextImpl implements ESContext {

  @Override
  public Logger logger(Class<?> clazz) {
    return Loggers.getLogger(clazz);
  }
}
