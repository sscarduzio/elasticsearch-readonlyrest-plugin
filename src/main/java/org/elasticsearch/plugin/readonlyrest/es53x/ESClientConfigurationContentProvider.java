package org.elasticsearch.plugin.readonlyrest.es53x;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.plugin.readonlyrest.configuration.ConfigurationContentProvider;

import java.util.concurrent.CompletableFuture;

public class ESClientConfigurationContentProvider implements ConfigurationContentProvider {

  private final Client client;

  public ESClientConfigurationContentProvider(Client client) {
    this.client = client;
  }

  @Override
  public CompletableFuture<String> getConfiguration() {
    return CompletableFuture.supplyAsync(() -> {
      GetResponse resp = client.prepareGet(".readonlyrest", "settings", "1").get();
      if (!resp.isExists()) {
        throw new ElasticsearchException("no settings found in index");
      }
      return (String) resp.getSource().get("settings");
    });
  }
}
