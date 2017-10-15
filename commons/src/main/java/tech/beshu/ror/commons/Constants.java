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
package tech.beshu.ror.commons;

import cz.seznam.euphoria.shaded.guava.com.google.common.base.Strings;

import java.io.File;

public class Constants {
  public static final Integer CACHE_WATERMARK = 1024;
  public static final String ANSI_RESET = "\u001B[0m";
  public static final String ANSI_BLACK = "\u001B[30m";
  public static final String ANSI_RED = "\u001B[31m";
  public static final String ANSI_GREEN = "\u001B[32m";
  public static final String ANSI_YELLOW = "\u001B[33m";
  public static final String ANSI_BLUE = "\u001B[34m";
  public static final String ANSI_PURPLE = "\u001B[35m";
  public static final String ANSI_CYAN = "\u001B[36m";
  public static final String ANSI_WHITE = "\u001B[37m";
  public static final Integer AUDIT_SINK_MAX_ITEMS = 100;
  public static final Integer AUDIT_SINK_MAX_KB = 100;
  public static final Integer AUDIT_SINK_MAX_SECONDS = 2;
  public static final Integer AUDIT_SINK_MAX_RETRIES = 3;
  public static final String SETTINGS_YAML_FILE = "readonlyrest.yml";
  private static final String esHomePath = System.getProperty("es.path.home");

  public static String makeAbsolutePath(String path) {
    if (Strings.isNullOrEmpty(esHomePath)) {
      new Exception("Cannot find property es.path.home!").printStackTrace();
    }
    if (path != null && !path.startsWith(File.separator)) {
      return esHomePath + File.separator + path;
    }
    return path;
  }

}
