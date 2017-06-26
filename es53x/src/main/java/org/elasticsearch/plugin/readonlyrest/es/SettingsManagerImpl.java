package org.elasticsearch.plugin.readonlyrest.es;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentType;
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
