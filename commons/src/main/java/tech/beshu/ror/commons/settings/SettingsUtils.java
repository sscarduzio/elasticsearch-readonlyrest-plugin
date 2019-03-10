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

package tech.beshu.ror.commons.settings;

import com.google.common.collect.Maps;
import com.google.gson.Gson;
import org.yaml.snakeyaml.DumperOptions;
//import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import tech.beshu.ror.commons.shims.es.LoggerShim;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Map;

public class SettingsUtils {

  private final static Gson gson = new Gson();

  private static Yaml yamlDumper;
  private static Yaml yamlLoader;

  static {
    // #TODO Elasticsearch ships with an old version of snakeyaml, so it's not yet possible to avoid duplicate keys in Yaml

    DumperOptions dumperOptions = new DumperOptions();
    //LoaderOptions loaderOptions = new LoaderOptions();
    //loaderOptions.setAllowDuplicateKeys(false);
    dumperOptions.setExplicitEnd(false);
    dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.AUTO);
    dumperOptions.setIndent(2);
    dumperOptions.setWidth(360);
    dumperOptions.setCanonical(false);
    dumperOptions.setPrettyFlow(false);
    dumperOptions.setExplicitStart(false);
    yamlDumper = new Yaml(dumperOptions);
    yamlLoader = yamlDumper; //new Yaml(loaderOptions);
  }

  public static String map2yaml(Map<String, ?> map) {
    // this is already a map, and we don't need to check for duplicate keys.
    // no need for dump-load-dump for checking
    return yamlDumper.dump(map);
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
        m[0] = yamlLoader.load(s);
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
    // Test for duplicate keys, throw if invalid
    yamlLoader.load(yaml);
    Map<String, String> tmpMap = Maps.newHashMap();
    tmpMap.put("settings", yaml);
    return map2Json(tmpMap);
  }

}
