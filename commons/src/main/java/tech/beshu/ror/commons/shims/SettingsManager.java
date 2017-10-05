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

package tech.beshu.ror.commons.shims;


import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Created by sscarduzio on 25/06/2017.
 */
public interface SettingsManager {
  String fileName = "readonlyrest.yml";

  Map<String, ?> getSettingsFromES();

  Map<String, ?> mkSettingsFromYAMLString(String yamlString);

  Map<String, ?> reloadSettingsFromIndex();

  boolean isClusterReady();

  ESContext getContext();

  default Map<String, ?> getSettings() {
    Map<String, ?> fromES = getSettingsFromES();

    String filePath = Optional.ofNullable((String) fromES.get("path.conf")).orElse("config" + File.separator);
    if (!filePath.endsWith(File.separator)) {
      filePath += File.separator;
    }
    filePath += fileName;

    String finalFilePath = filePath;

    final Map<String, Object> settingsMap = new HashMap<>();
    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
      try {
        String slurped = new String(Files.readAllBytes(Paths.get(finalFilePath)));
        settingsMap.putAll(mkSettingsFromYAMLString(slurped));

        getContext().logger(getClass()).info("Loaded good settings from " + finalFilePath);
      } catch (Throwable t) {
        getContext().logger(getClass()).info(
          "Could not find settings in "
            + finalFilePath + ", falling back to elasticsearch.yml (" + t.getMessage() + ")");
        settingsMap.putAll(getSettingsFromES());
      }
      return null;
    });
    return settingsMap;

  }
}
