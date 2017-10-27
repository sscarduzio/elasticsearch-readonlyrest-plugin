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

import com.google.common.collect.Maps;
import com.google.gson.Gson;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Map;

public class SettingsForStorage {

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

  private String originalYamlString;

  public SettingsForStorage(String originalYamlString) {
    // Ensure it is YAML
    try{
      originalYamlString = SettingsForStorage.extractYAMLfromJSONStorage(originalYamlString);
    }
    catch (Exception e){
      // It's ok, maybe it's not wrapped in JSON.
    }
    try{
      // Has to be parsable YAML now
      yaml2Map(originalYamlString);
    }
    catch (Exception e){
      e.printStackTrace();
      throw e;
    }

    this.originalYamlString = originalYamlString;
  }

  private static String extractYAMLfromJSONStorage(String jsonWrappedYAML) {
    final String[] s = new String[1];
    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
      s[0] = (String) gson.fromJson(jsonWrappedYAML, Map.class).get("settings");
      return null;
    });
    return s[0];
  }

  public String asRawYAML() {
    return originalYamlString;
  }

  public String toJsonStorage() {

    Map<String, String> tmpMap = Maps.newHashMap();
    tmpMap.put("settings", originalYamlString);
    return toJson(tmpMap);

  }

  public Map<String, ?> asMap() {
    return yaml2Map(originalYamlString);
  }

  private Map<String, ?> yaml2Map(String s) {
    final Map[] m = new Map[1];
    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {

      String j = toJson((Map) yaml.load(s));
      m[0] = gson.fromJson(j, Map.class);
      return null;
    });
    return m[0];
  }

  private String toJson(Map m) {
    final String[] jsonToCommit = new String[1];
    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
      jsonToCommit[0] = gson.toJson(m);
      return null;
    });
    return jsonToCommit[0];
  }

}
