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

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.configuration.ReloadableSettings;
import org.elasticsearch.plugin.readonlyrest.configuration.SettingsManager;

import java.io.IOException;
import java.util.Map;

/**
 * Created by sscarduzio on 25/06/2017.
 */

@Singleton
public class SettingsManagerImpl implements SettingsManager {

  private final NodeClient client;
  private Settings settings;

  @Inject
  public SettingsManagerImpl(Settings settings, NodeClient client) throws IOException {
    this.settings = settings;
    this.client = client;
  }

  @Override
  public Map<String, ?> getSettingsFromES() {
    return settings.getAsStructuredMap();
  }

  @Override
  public Map<String, ?> mkSettingsFromYAMLString(String yamlString) {
    return Settings.builder().loadFromSource(yamlString, XContentType.YAML).build().getAsStructuredMap();
  }

  public Map<String, ?> reloadSettingsFromIndex() {
    GetResponse resp = null;
    try {
      resp = client.prepareGet(".readonlyrest", "settings", "1").get();
    } catch (Throwable t) {
      if (t instanceof ResourceNotFoundException) {
        throw new ElasticsearchException(ReloadableSettings.SETTINGS_NOT_FOUND_MESSAGE);
      }
      throw new ElasticsearchException(t.getMessage());
    }
    if (resp == null || !resp.isExists()) {
      throw new ElasticsearchException(ReloadableSettings.SETTINGS_NOT_FOUND_MESSAGE);
    }
    String yamlString = (String) resp.getSource().get("settings");
    Settings settingsFromIndex = Settings.builder().loadFromSource(yamlString, XContentType.YAML).build();
    return settingsFromIndex.getAsStructuredMap();
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

  @Override
  public ESContext getContext() {
    return new ESContextImpl();
  }

}
