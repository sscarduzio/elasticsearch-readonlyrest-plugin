package org.elasticsearch.plugin.readonlyrest;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.plugin.readonlyrest.httpclient.HttpClientFactory;

public interface ESContext {
  Logger logger(Class<?> clazz);
  RuntimeException rorException(String message);
  HttpClientFactory httpClientFactory();
}
