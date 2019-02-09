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

package tech.beshu.ror.settings;

import com.google.gson.Gson;
import cz.seznam.euphoria.shaded.guava.com.google.common.collect.Maps;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import tech.beshu.ror.shims.es.LoggerShim;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Map;

public class SettingsUtils {

  private final static Gson gson = new Gson();
  private static DumperOptions options = new DumperOptions();
  private static Yaml yaml = new Yaml(options);

  static {
    options.setExplicitEnd(false);
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.AUTO);
    options.setIndent(2);
    options.setWidth(360);
    options.setCanonical(false);
    options.setPrettyFlow(false);
    options.setExplicitStart(false);
  }


  public static String map2yaml(Map<String, ?> map) {
    return yaml.dump(map);
  }

  public static String extractYAMLfromJSONStorage(String jsonWrappedYAML) {
    final String[] s = new String[1];
    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
      s[0] = (String) gson.fromJson(jsonWrappedYAML, Map.class).get("settings");
      return null;
    });
    return s[0];
  }

  public static Map<String, ?> yaml2Map(String s, LoggerShim logger) {
    final Map[] m = new Map[1];
    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
      try {
        m[0] = yaml.load(s);
      } catch (Exception e) {
        logger.error("Cannot parse YAML: " + e.getClass().getSimpleName() + ":" + e.getMessage() + "\n " + s, e);
      }
      return null;
    });
    return m[0];
  }

  private static String map2Json(Map<String, ?> m) {
    final String[] jsonToCommit = new String[1];
    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
      jsonToCommit[0] = gson.toJson(m);
      return null;
    });
    return jsonToCommit[0];
  }


  public static String toJsonStorage(String yaml) {

    Map<String, String> tmpMap = Maps.newHashMap();
    tmpMap.put("settings", yaml);
    return map2Json(tmpMap);

  }

}
