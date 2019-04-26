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

import com.google.common.collect.Sets;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.support.ActionFilter;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.IndexScopedSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsFilter;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.PageCacheRecycler;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.http.HttpServerTransport;
import org.elasticsearch.index.IndexModule;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.plugins.ActionPlugin;
import org.elasticsearch.plugins.IngestPlugin;
import org.elasticsearch.plugins.NetworkPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.ScriptPlugin;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestHandler;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.Transport;
import org.elasticsearch.watcher.ResourceWatcherService;
import tech.beshu.ror.Constants;
import tech.beshu.ror.configuration.AllowedSettings;
import tech.beshu.ror.es.rradmin.RRAdminAction;
import tech.beshu.ror.es.rradmin.TransportRRAdminAction;
import tech.beshu.ror.es.rradmin.rest.RestRRAdminAction;
import tech.beshu.ror.es.security.RoleIndexSearcherWrapper;
import tech.beshu.ror.settings.BasicSettings;
import tech.beshu.ror.shims.es.LoggerShim;

import java.nio.file.Path;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

public class ReadonlyRestPlugin extends Plugin
    implements ScriptPlugin, ActionPlugin, IngestPlugin, NetworkPlugin {

  private final Settings settings;
  private final BasicSettings basicSettings;
  private final LoggerShim logger;

  private IndexLevelActionFilter ilaf;
  private SettingsObservableImpl settingsObservable;
  private Environment environment;

  @Inject
  public ReadonlyRestPlugin(Settings s, Path p) {
    this.settings = s;
    this.environment = new Environment(s, p);
    Constants.FIELDS_ALWAYS_ALLOW.addAll(Sets.newHashSet(MapperService.getAllMetaFields()));
    this.logger = ESContextImpl.mkLoggerShim(Loggers.getLogger(getClass(), getClass().getSimpleName()));
    this.basicSettings = BasicSettings.fromFileObj(logger, this.environment.configFile().toAbsolutePath(), settings);
  }

  @Override
  public Collection<Object> createComponents(Client client, ClusterService clusterService, ThreadPool threadPool, ResourceWatcherService resourceWatcherService,
      ScriptService scriptService, NamedXContentRegistry xContentRegistry, Environment environment, NodeEnvironment nodeEnvironment,
      NamedWriteableRegistry namedWriteableRegistry) {

    final List<Object> components = new ArrayList<>(3);

    // Wrap all ROR logic into privileged action
    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
      this.environment = environment;
      settingsObservable = new SettingsObservableImpl((NodeClient) client, settings, environment);
      this.ilaf = new IndexLevelActionFilter(settings, clusterService, (NodeClient) client, threadPool, settingsObservable, environment, hasRemoteClusters(clusterService));
      components.add(settingsObservable);
      return null;
    });

    return components;
  }

  @Override
  public List<ActionFilter> getActionFilters() {
    return Collections.singletonList(ilaf);
  }

  @Override
  public void onIndexModule(IndexModule indexModule) {
    indexModule.setSearcherWrapper(indexService -> {
      try {
        return new RoleIndexSearcherWrapper(indexService, this.settings, this.environment);
      } catch (Exception e) {
        e.printStackTrace();
      }
      return null;
    });
  }

  @Override
  public Map<String, Supplier<HttpServerTransport>> getHttpTransports(
      Settings settings,
      ThreadPool threadPool,
      BigArrays bigArrays,
      PageCacheRecycler pageCacheRecycler,
      CircuitBreakerService circuitBreakerService,
      NamedXContentRegistry xContentRegistry,
      NetworkService networkService,
      HttpServerTransport.Dispatcher dispatcher) {

    if (!basicSettings.getSslHttpSettings().map(x -> x.isSSLEnabled()).orElse(false)) {
      return Collections.EMPTY_MAP;
    }

    return Collections.singletonMap(
        "ssl_netty4", () ->
            new SSLNetty4HttpServerTransport(
                settings, networkService, bigArrays, threadPool, xContentRegistry, dispatcher, environment
            ));
  }

  @Override
  public Map<String, Supplier<Transport>> getTransports(Settings settings, ThreadPool threadPool, PageCacheRecycler pageCacheRecycler,
      CircuitBreakerService circuitBreakerService, NamedWriteableRegistry namedWriteableRegistry, NetworkService networkService) {

    if (!basicSettings.getSslInternodeSettings().map(x -> x.isSSLEnabled()).orElse(false)) {
      return Collections.EMPTY_MAP;
    }

    return Collections.singletonMap("ror_ssl_internode", () ->
        new SSLNetty4InternodeServerTransport(
            settings, threadPool, pageCacheRecycler, circuitBreakerService, namedWriteableRegistry, networkService, basicSettings.getSslHttpSettings().get())
    );
  }

  @Override
  public void close() {
    ESContextImpl.shutDownObservable.shutDown();
  }

  @Override
  public List<Setting<?>> getSettings() {
    // No need, we have settings in config/readonlyrest.yml
    //return super.getSettings();
    return AllowedSettings.list().entrySet().stream().map((e) -> {
      Setting<?> theSetting = null;
      switch (e.getValue()) {
        case BOOL:
          theSetting = Setting.boolSetting(e.getKey(), Boolean.FALSE, Setting.Property.NodeScope);
          break;
        case STRING:
          theSetting = new Setting<>(e.getKey(), "", (value) -> value, Setting.Property.NodeScope);
          break;
        case GROUP:
          theSetting = Setting.groupSetting(e.getKey(), Setting.Property.Dynamic, Setting.Property.NodeScope);
          break;
        default:
          throw new ElasticsearchException("invalid settings " + e);
      }
      return theSetting;
    }).collect(Collectors.toList());
  }

  @Override
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public List<ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {
    return Collections.singletonList(
        new ActionHandler(RRAdminAction.INSTANCE, TransportRRAdminAction.class));
  }

  @Override
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public List<RestHandler> getRestHandlers(
      Settings settings, RestController restController, ClusterSettings clusterSettings,
      IndexScopedSettings indexScopedSettings, SettingsFilter settingsFilter,
      IndexNameExpressionResolver indexNameExpressionResolver, Supplier<DiscoveryNodes> nodesInCluster) {
    return Collections.singletonList(new RestRRAdminAction(settings, restController));
  }

  @Override
  public UnaryOperator<RestHandler> getRestHandlerWrapper(ThreadContext threadContext) {
    return restHandler -> (RestHandler) (request, channel, client) -> {
      // Need to make sure we've fetched cluster-wide configuration at least once. This is super fast, so NP.
      ThreadRepo.channel.set(channel);
      restHandler.handleRequest(request, channel, client);
    };
  }

  private boolean hasRemoteClusters(ClusterService clusterService) {
    try {
      return !clusterService.getSettings().getAsGroups().get("cluster").getGroups("remote").isEmpty();
    } catch (Exception ex) {
      if(logger.isDebugEnabled()) {
        logger.warn("could not check if had remote ES clusters", ex);
      } else {
        logger.warn("could not check if had remote ES clusters: " + ex.getMessage());
      }
      return false;
    }
  }
}
