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
  public final static String REST_REFRESH_PATH = "/_readonlyrest/admin/refreshconfig";
  public final static String REST_CONFIGURATION_PATH = "/_readonlyrest/admin/config";
  public final static String REST_CONFIGURATION_FILE_PATH = "/_readonlyrest/admin/config/file";
  public final static String FILTER_TRANSIENT = "_filter";
  public final static String FIELDS_TRANSIENT = "_fields";
  public static final String HEADER_GROUPS_AVAILABLE = "x-ror-available-groups";
  public static final String HEADER_GROUP_CURRENT = "x-ror-current-group";
  public static final String HEADER_USER_ROR = "X-RR-User";
  public static final boolean KIBANA_METADATA_ENABLED =
      !"false".equalsIgnoreCase(System.getProperty("com.readonlyrest.kibana.metadata"));

  public static String makeAbsolutePath(String path, String basePath) {

    if (Strings.isNullOrEmpty(basePath)) {
      new Exception("Cannot find readonlyrest plugin base path!").printStackTrace();
    }
    if (path != null && !path.startsWith(File.separator)) {
      return basePath + File.separator + path;
    }
    return path;
  }

}
