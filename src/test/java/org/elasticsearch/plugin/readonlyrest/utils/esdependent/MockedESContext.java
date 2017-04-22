package org.elasticsearch.plugin.readonlyrest.utils.esdependent;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.plugin.readonlyrest.es53x.ESContext;

public class MockedESContext implements ESContext {

  public static final ESContext INSTANCE = new MockedESContext();

  @Override
  public Logger logger(Class<?> clazz) {
    return Loggers.getLogger(clazz);
  }
}
