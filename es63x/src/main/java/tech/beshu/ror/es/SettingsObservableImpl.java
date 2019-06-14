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

import com.google.common.util.concurrent.FutureCallback;
import org.apache.logging.log4j.LogManager;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.env.Environment;
import tech.beshu.ror.settings.__old_BasicSettings;
import tech.beshu.ror.settings.__old_RawSettings;
import tech.beshu.ror.settings.__old_SettingsObservable;
import tech.beshu.ror.settings.__old_SettingsUtils;
import tech.beshu.ror.shims.es.LoggerShim;

import java.nio.file.Path;

/**
 * Created by sscarduzio on 25/06/2017.
 */

@Singleton
public class SettingsObservableImpl extends __old_SettingsObservable {
  private static final LoggerShim logger = ESContextImpl.mkLoggerShim(LogManager.getLogger(SettingsObservableImpl.class));

  private final NodeClient client;
  private final Environment environment;

  @Inject
  public SettingsObservableImpl(NodeClient client, Settings s, Environment env) {
    this.environment = env;
    this.client = client;
    current = __old_BasicSettings.fromFileObj(logger, env.configFile().toAbsolutePath(), s).getRaw();
  }

  @Override
  protected Path getConfigPath() {
    return environment.configFile().toAbsolutePath();
  }

  @Override
  protected LoggerShim getLogger() {
    return logger;
  }

  protected __old_RawSettings getFromIndex() {
    GetResponse resp;
    try {
      resp = client.prepareGet(".readonlyrest", "settings", "1").get();
    } catch (ResourceNotFoundException rnfe) {
      throw new ElasticsearchException(SETTINGS_NOT_FOUND_MESSAGE, rnfe);
    } catch (Throwable t) {
      throw new ElasticsearchException(t.getMessage(), t);
    }
    if (resp == null || !resp.isExists()) {
      throw new ElasticsearchException(SETTINGS_NOT_FOUND_MESSAGE, new ElasticsearchException("null response from index query"));
    }
    String yamlString = (String) resp.getSource().get("settings");
    return new __old_RawSettings(yamlString, logger);
  }

  @Override
  protected void writeToIndex(__old_RawSettings rawSettings, FutureCallback f) {
    client.prepareBulk().add(
        client.prepareIndex(".readonlyrest", "settings", "1")
              .setSource(__old_SettingsUtils.toJsonStorage(rawSettings.yaml()), XContentType.JSON).request()
    ).execute(new ActionListener<BulkResponse>() {
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
      return !status.equals(ClusterHealthStatus.RED);
    } catch (Throwable e) {
      return false;
    }
  }

}
