package org.elasticsearch.plugin.readonlyrest;

/**
 * Created by sscarduzio on 02/07/2017.
 */
public interface LoggerShim {

  void trace(String message);
  void info(String message);
  void debug(String message);
  void warn(String message);
  void warn(String message, Throwable t);
  void error(String message, Throwable t);
  void error(String message);
  boolean isDebugEnabled();
}
