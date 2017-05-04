package org.elasticsearch.plugin.readonlyrest.es53x;


import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.plugin.readonlyrest.ESContext;

public class ESContextImpl implements ESContext {

  @Override
  public Logger logger(Class<?> clazz) {
    return Loggers.getLogger(clazz);
  }

  @Override
  public RuntimeException rorException(String message) {
    return new ElasticsearchException(message);
  }
}
