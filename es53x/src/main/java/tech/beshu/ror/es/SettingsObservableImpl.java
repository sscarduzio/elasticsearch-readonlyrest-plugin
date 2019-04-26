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

import java.nio.file.Path;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.env.Environment;

import com.google.common.util.concurrent.FutureCallback;
import tech.beshu.ror.settings.BasicSettings;
import tech.beshu.ror.settings.RawSettings;
import tech.beshu.ror.settings.SettingsObservable;
import tech.beshu.ror.settings.SettingsUtils;
import tech.beshu.ror.shims.es.LoggerShim;

/**
 * Created by sscarduzio on 25/06/2017.
 */

@Singleton
public class SettingsObservableImpl extends SettingsObservable {
  private static final LoggerShim logger = ESContextImpl.mkLoggerShim(Loggers.getLogger(SettingsObservableImpl.class));

  private final NodeClient client;
  private final Environment environment;

  @Inject
  public SettingsObservableImpl(NodeClient client, Settings s, Environment env) {
    this.environment = env;
    this.client = client;
    current = BasicSettings.fromFile(logger, environment.configFile().toAbsolutePath(), s.getAsStructuredMap()).getRaw();
  }

  @Override
  protected Path getConfigPath() {
    return environment.configFile().toAbsolutePath();
  }

  @Override
  protected LoggerShim getLogger() {
    return logger;
  }

  protected RawSettings getFromIndex() {
    GetResponse resp;
    try {
      resp = client.prepareGet(".readonlyrest", "settings", "1").get();
    } catch (ResourceNotFoundException rnfe) {
      throw new ElasticsearchException(SETTINGS_NOT_FOUND_MESSAGE);
    } catch (Throwable t) {
      throw new ElasticsearchException(t.getMessage());
    }
    if (resp == null || !resp.isExists()) {
      throw new ElasticsearchException(SETTINGS_NOT_FOUND_MESSAGE);
    }
    String yamlString = (String) resp.getSource().get("settings");
    return new RawSettings(yamlString, logger);
  }

  @Override
  protected void writeToIndex(RawSettings rawSettings, FutureCallback f) {
    client.prepareBulk().add(
      client.prepareIndex(".readonlyrest", "settings", "1")
        .setSource(SettingsUtils.toJsonStorage(rawSettings.yaml()), XContentType.JSON).request()
    ).execute().addListener(new ActionListener<BulkResponse>() {
      @Override
      public void onResponse(BulkResponse bulkItemResponses) {
        logger.info("all ok, written settings");
        f.onSuccess(bulkItemResponses);
      }

      @Override
      public void onFailure(Exception e) {
        logger.error("could not write settings to index: ", e);
        f.onFailure(e);
      }
    });

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
