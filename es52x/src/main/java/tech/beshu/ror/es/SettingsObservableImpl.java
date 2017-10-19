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

package tech.beshu.ror.es;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.loader.YamlSettingsLoader;
import tech.beshu.ror.commons.SettingsObservable;
import tech.beshu.ror.commons.shims.es.LoggerShim;
import tech.beshu.ror.commons.utils.SettingsUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static tech.beshu.ror.commons.Constants.SETTINGS_YAML_FILE;

/**
 * Created by sscarduzio on 25/06/2017.
 */

@Singleton
public class SettingsObservableImpl extends SettingsObservable {
  private static final LoggerShim logger = ESContextImpl.mkLoggerShim(Loggers.getLogger(SettingsObservableImpl.class));

  private final NodeClient client;
  private Settings settings;

  @Inject
  public SettingsObservableImpl(Settings settings, NodeClient client) {
    this.settings = settings;
    this.client = client;
    current = this.getFromFileWithFallbackToES();
  }

  @Override
  protected Map<String, ?> getFomES() {
    return settings.getAsStructuredMap();
  }

  @Override
  protected Map<String, ?> getFromFileWithFallbackToES() {
    Map<String, ?> fromES = getFomES();

    String filePath = Optional.ofNullable((String) fromES.get("path.conf")).orElse("config" + File.separator);
    if (!filePath.endsWith(File.separator)) {
      filePath += File.separator;
    }
    filePath += SETTINGS_YAML_FILE;

    String finalFilePath = filePath;

    final Map<String, Object> settingsMap = new HashMap<>();
    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
      try {
        String slurped = new String(Files.readAllBytes(Paths.get(finalFilePath)));
        settingsMap.putAll(mkSettingsFromYAMLString(slurped));

        logger.info("Loaded good settings from " + finalFilePath);
      } catch (Throwable t) {
        logger.info(
          "Could not find settings in "
            + finalFilePath + ", falling back to elasticsearch.yml (" + t.getMessage() + ")");
        settingsMap.putAll(getFomES());
      }
      return null;
    });
    return settingsMap;
  }

  @Override
  public Map<String, ?> mkSettingsFromYAMLString(String yamlString) {
    Map<String, ?> flat = null;
    try {
      flat = SettingsUtils.getAsStructuredMap(new YamlSettingsLoader(true).load(yamlString));
    } catch (IOException e) {
      e.printStackTrace();
    }
    return flat;
  }

  @Override
  protected LoggerShim getLogger() {
    return logger;
  }

  protected Map<String, ?> getFromIndex() {
    GetResponse resp = null;
    try {
      resp = client.prepareGet(".readonlyrest", "settings", "1").get();
    } catch (Throwable t) {
      if (t instanceof ResourceNotFoundException) {
        throw new ElasticsearchException(SETTINGS_NOT_FOUND_MESSAGE);
      }
      throw new ElasticsearchException(t.getMessage());
    }
    if (resp == null || !resp.isExists()) {
      throw new ElasticsearchException(SETTINGS_NOT_FOUND_MESSAGE);
    }
    String yamlString = (String) resp.getSource().get("settings");
    return mkSettingsFromYAMLString(yamlString);
  }

  @Override
  public boolean isClusterReady() {
    try {
      ClusterHealthStatus status = client.admin().cluster().prepareHealth().get().getStatus();
      Boolean ready = !status.equals(ClusterHealthStatus.RED);
      return ready;
    } catch (Throwable e) {
      return false;
    }
  }

}
