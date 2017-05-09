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
package org.elasticsearch.plugin.readonlyrest.utils.esdependent;

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
