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
