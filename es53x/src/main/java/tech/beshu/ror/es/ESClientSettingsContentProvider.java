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

import com.google.common.util.concurrent.MoreExecutors;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.client.Client;
import tech.beshu.ror.commons.shims.es.SettingsContentProvider;

import java.util.concurrent.CompletableFuture;

public class ESClientSettingsContentProvider implements SettingsContentProvider {

  private final Client client;

  public ESClientSettingsContentProvider(Client client) {
    this.client = client;
  }

  @Override
  public CompletableFuture<String> getSettingsContent() {
    return CompletableFuture.supplyAsync(() -> {
      GetResponse resp = null;
      try {
        resp = client.prepareGet(".readonlyrest", "settings", "1").get();
      } catch (Throwable t) {
        throw new ElasticsearchException(t.getMessage());
      }
      if (resp == null || !resp.isExists()) {
        throw new ElasticsearchException("no settings found in index");
      }
      return (String) resp.getSource().get("settings");
    }, MoreExecutors.directExecutor());
  }
}
