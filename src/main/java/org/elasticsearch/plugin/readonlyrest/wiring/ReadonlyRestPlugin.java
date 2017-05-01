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

package org.elasticsearch.plugin.readonlyrest.wiring;

import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.support.ActionFilter;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.IndexScopedSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsFilter;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.http.HttpServerTransport;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.plugin.readonlyrest.ConfigurationHelper;
import org.elasticsearch.plugin.readonlyrest.IndexLevelActionFilter;
import org.elasticsearch.plugin.readonlyrest.SSLTransportNetty4;
import org.elasticsearch.plugin.readonlyrest.rradmin.RRAdminAction;
import org.elasticsearch.plugin.readonlyrest.rradmin.TransportRRAdminAction;
import org.elasticsearch.plugin.readonlyrest.rradmin.rest.RestRRAdminAction;
import org.elasticsearch.plugins.ActionPlugin;
import org.elasticsearch.plugins.IngestPlugin;
import org.elasticsearch.plugins.NetworkPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.ScriptPlugin;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestHandler;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.watcher.ResourceWatcherService;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

public class ReadonlyRestPlugin extends Plugin implements ScriptPlugin, ActionPlugin, IngestPlugin, NetworkPlugin {
  private final Settings settings;
  private final ESLogger logger = Loggers.getLogger(this.getClass());

  public ReadonlyRestPlugin(Settings s) {
    this.settings = s;
  }

  @Override
  public List<Class<? extends ActionFilter>> getActionFilters() {
    return Collections.singletonList(IndexLevelActionFilter.class);
  }

  @Override
  public Map<String, Supplier<HttpServerTransport>> getHttpTransports(
      Settings settings,
      ThreadPool threadPool,
      BigArrays bigArrays,
      CircuitBreakerService circuitBreakerService,
      NamedWriteableRegistry namedWriteableRegistry,
      NamedXContentRegistry xContentRegistry,
      NetworkService networkService,
      HttpServerTransport.Dispatcher dispatcher) {

    return Collections.singletonMap(
        "ssl_netty4", () -> new SSLTransportNetty4(settings, networkService, bigArrays, threadPool, xContentRegistry, dispatcher));
  }


  @Override
  public Collection<Object> createComponents(Client client, ClusterService clusterService, ThreadPool threadPool,
      ResourceWatcherService resourceWatcherService, ScriptService scriptService,
      NamedXContentRegistry xContentRegistry) {

    Collection<Object> fromSup = super.createComponents(client, clusterService, threadPool, resourceWatcherService,
        scriptService, xContentRegistry
    );
    ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
    Runnable task = new Runnable() {
      @Override
      public void run() {
        try {
          logger.debug("[CLUSTERWIDE SETTINGS] checking index..");
          ConfigurationHelper.getInstance(settings, client).updateSettingsFromIndex(client);
          logger.info("Cluster-wide settings found, overriding elasticsearch.yml");
          executor.shutdown();
        } catch (ElasticsearchException ee) {
          logger.info("[CLUSTERWIDE SETTINGS] settings not found, please install ReadonlyREST Kibana plugin." +
              " Will keep on using elasticearch.yml.");
          executor.shutdown();
        } catch (Throwable t) {
          logger.debug("[CLUSTERWIDE SETTINGS] index not ready yet..");
          executor.schedule(this, 200, TimeUnit.MILLISECONDS);
        }
      }
    };
    executor.schedule(task, 200, TimeUnit.MILLISECONDS);
    return fromSup;
  }

  @Override
  @SuppressWarnings({"unchecked", "rawtypes"})
  public List<ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {
    return Collections.singletonList(
        new ActionHandler(RRAdminAction.INSTANCE, TransportRRAdminAction.class, new Class[0]));
  }


  @Override
  @SuppressWarnings({"unchecked", "rawtypes"})
  public List<RestHandler> getRestHandlers(
      Settings settings, RestController restController, ClusterSettings clusterSettings,
      IndexScopedSettings indexScopedSettings, SettingsFilter settingsFilter,
      IndexNameExpressionResolver indexNameExpressionResolver, Supplier<DiscoveryNodes> nodesInCluster) {
    return Collections.singletonList(new RestRRAdminAction(settings, restController));
  }

  @Override
  public UnaryOperator<RestHandler> getRestHandlerWrapper(ThreadContext threadContext) {
    return restHandler -> new RestHandler() {
      @Override
      public void handleRequest(RestRequest request, RestChannel channel, NodeClient client) throws Exception {
        // Need to make sure we've fetched cluster-wide configuration at least once. This is super fast, so NP.
        ConfigurationHelper.getInstance(settings, client);
        ThreadRepo.channel.set(channel);
        restHandler.handleRequest(request, channel, client);
      }
    };
  }

}
