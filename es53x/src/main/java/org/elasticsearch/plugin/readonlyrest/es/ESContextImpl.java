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
package org.elasticsearch.plugin.readonlyrest.es;


import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.LoggerShim;

public class ESContextImpl implements ESContext {

  public static LoggerShim mkLoggerShim(Logger l) {
    return new LoggerShim() {

      @Override
      public void trace(String message) {
        l.trace(message);
      }

      @Override
      public void info(String message) {
        l.info(message);
      }

      @Override
      public void debug(String message) {
        l.debug(message);
      }

      @Override
      public void warn(String message) {
        l.warn(message);
      }

      @Override
      public void warn(String message, Throwable t) {
        l.warn(message);
        t.printStackTrace();
      }

      @Override
      public void error(String message, Throwable t) {
        l.error(message);
        t.printStackTrace();
      }

      @Override
      public void error(String message) {
        l.error(message);
      }

      @Override
      public boolean isDebugEnabled() {
        return l.isDebugEnabled();
      }
    };
  }

  @Override
  public LoggerShim logger(Class<?> clazz) {
    return mkLoggerShim(Loggers.getLogger(clazz));
  }

  @Override
  public RuntimeException rorException(String message) {
    return new ElasticsearchException(message);
  }

}
