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
import org.elasticsearch.env.Environment;
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
import org.elasticsearch.threadpool.ThreadPool;
import tech.beshu.ror.commons.Constants;
import tech.beshu.ror.commons.settings.BasicSettings;
import tech.beshu.ror.commons.shims.es.AbstractESContext;
import tech.beshu.ror.commons.shims.es.LoggerShim;
import tech.beshu.ror.configuration.AllowedSettings;
import tech.beshu.ror.es.rradmin.RRAdminAction;
import tech.beshu.ror.es.rradmin.TransportRRAdminAction;
import tech.beshu.ror.es.rradmin.rest.RestRRAdminAction;
import tech.beshu.ror.es.security.RoleIndexSearcherWrapper;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

public class ReadonlyRestPlugin extends Plugin
    implements ScriptPlugin, ActionPlugin, IngestPlugin, NetworkPlugin {

  private final LoggerShim logger;
  private final BasicSettings basicSettings;
  private Settings settings;
  private Environment environment;

  public ReadonlyRestPlugin(Settings s) {
    this.settings = s;
    this.environment = new Environment(s);
    Constants.FIELDS_ALWAYS_ALLOW.addAll(Sets.newHashSet(MapperService.getAllMetaFields()));
    this.logger = ESContextImpl.mkLoggerShim(Loggers.getLogger(getClass().getName()));
    basicSettings = BasicSettings.fromFileObj(logger, this.environment.configFile().toAbsolutePath(), settings);
  }

  @Override
  public void close() {
    AbstractESContext.shutDownObservable.shutDown();
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

    if (!basicSettings.getSslHttpSettings().map(x -> x.isSSLEnabled()).orElse(false)) {
      return Collections.EMPTY_MAP;
    }

    return Collections.singletonMap(
        "ssl_netty4", () ->
            new SSLTransportNetty4(
                settings, networkService, bigArrays, threadPool, xContentRegistry, dispatcher, basicSettings.getSslHttpSettings().get()
            ));
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

  @Override
  public void onIndexModule(IndexModule module) {
    module.setSearcherWrapper(indexService -> {
      try {
        return new RoleIndexSearcherWrapper(indexService, this.settings, this.environment);
      } catch (Exception e) {
        e.printStackTrace();
      }
      return null;
    });
  }

}
