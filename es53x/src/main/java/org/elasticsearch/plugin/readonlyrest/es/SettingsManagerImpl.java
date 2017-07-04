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
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.plugin.readonlyrest.configuration.SettingsManager;

import java.io.IOException;
import java.util.Map;

/**
 * Created by sscarduzio on 25/06/2017.
 */

public class SettingsManagerImpl implements SettingsManager {

  private final NodeClient client;
  private Settings settings;

  public SettingsManagerImpl(Settings settings, NodeClient client) throws IOException {
    this.settings = settings;
    this.client = client;
  }

  public Map<String, ?> getCurrentSettings() {
    return settings.getAsStructuredMap();
  }

  public Map<String, ?> reloadSettingsFromIndex() {
    GetResponse resp = null;
    try {
      resp = client.prepareGet(".readonlyrest", "settings", "1").get();
    } catch (Throwable t) {
      throw new ElasticsearchException(t.getMessage());
    }
    if (resp == null || !resp.isExists()) {
      throw new ElasticsearchException("no settings found in index");
    }
    String yamlString = (String) resp.getSource().get("settings");
    Settings settingsFromIndex = Settings.builder().loadFromSource(yamlString, XContentType.YAML).build();
    return settingsFromIndex.getAsStructuredMap();
  }

}
