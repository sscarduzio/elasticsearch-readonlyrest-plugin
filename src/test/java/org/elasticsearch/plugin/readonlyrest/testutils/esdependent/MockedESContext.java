package org.elasticsearch.plugin.readonlyrest.testutils.esdependent;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.plugin.readonlyrest.ESContext;

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
}
