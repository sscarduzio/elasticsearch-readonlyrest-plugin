package org.elasticsearch.plugin.readonlyrest.testutils.esdependent;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.es53x.ESHttpClientFactory;
import org.elasticsearch.plugin.readonlyrest.httpclient.HttpClientFactory;

public class MockedESContext implements ESContext {

  public static final ESContext INSTANCE = new MockedESContext();

  @Override
  public Logger logger(Class<?> clazz) {
    return Loggers.getLogger(clazz);
  }

  @Override
  public RuntimeException rorException(String message) {
    return new RuntimeException(message);
  }

  @Override
  public HttpClientFactory httpClientFactory() {
    // todo: use different http client than RestClient
    return ESHttpClientFactory.INSTANCE;
  }
}
