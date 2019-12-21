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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import monix.execution.Scheduler$;
import monix.execution.schedulers.CanBlock$;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.support.ActionFilter;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.component.LifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.IndexScopedSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsFilter;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.PageCacheRecycler;
import org.elasticsearch.common.util.concurrent.EsExecutors;
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
import org.elasticsearch.transport.netty4.Netty4Utils;
import org.elasticsearch.watcher.ResourceWatcherService;
import scala.concurrent.duration.FiniteDuration;
import tech.beshu.ror.Constants;
import tech.beshu.ror.configuration.RorSsl;
import tech.beshu.ror.configuration.RorSsl$;
import tech.beshu.ror.es.dlsfls.RoleIndexSearcherWrapper;
import tech.beshu.ror.es.rradmin.RRAdminAction;
import tech.beshu.ror.es.rradmin.TransportRRAdminAction;
import tech.beshu.ror.es.rradmin.rest.RestRRAdminAction;
import tech.beshu.ror.es.ssl.SSLNetty4HttpServerTransport;
import tech.beshu.ror.es.ssl.SSLNetty4InternodeServerTransport;
import tech.beshu.ror.es.utils.ThreadRepo;
import tech.beshu.ror.utils.ScalaJavaHelper$;

import java.nio.file.Path;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

public class ReadonlyRestPlugin extends Plugin
    implements ScriptPlugin, ActionPlugin, IngestPlugin, NetworkPlugin {
  static {
    LogBuildInfoMessage.apply();
  }
  private final RorSsl sslConfig;
  private Environment environment;

  @Inject
  public ReadonlyRestPlugin(Settings s, Path p) {
    // ES uses Netty underlying and Finch also uses it under the hood. Seems that ES has reimplemented own available processor
    // flag check, which is also done by Netty. So, we need to set it manually before ES and Finch, otherwise we will
    // experience 'java.lang.IllegalStateException: availableProcessors is already set to [x], rejecting [x]' exception
    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
      Netty4Utils.setAvailableProcessors(EsExecutors.PROCESSORS_SETTING.get(s));
      return null;
    });
    this.environment = new Environment(s, p);
    Constants.FIELDS_ALWAYS_ALLOW.addAll(Sets.newHashSet(MapperService.getAllMetaFields()));
    FiniteDuration timeout = FiniteDuration.apply(10, TimeUnit.SECONDS);
    this.sslConfig = RorSsl$.MODULE$.load(environment.configFile())
        .map(result -> ScalaJavaHelper$.MODULE$.getOrElse(result, error -> new ElasticsearchException(error.message())))
        .runSyncUnsafe(timeout, Scheduler$.MODULE$.global(), CanBlock$.MODULE$.permit());
  }

  @Override
  public Collection<Object> createComponents(Client client, ClusterService clusterService, ThreadPool threadPool, ResourceWatcherService resourceWatcherService,
      ScriptService scriptService, NamedXContentRegistry xContentRegistry, Environment environment, NodeEnvironment nodeEnvironment,
      NamedWriteableRegistry namedWriteableRegistry) {
    final List<Object> components = new ArrayList<>(3);
    this.environment = environment;
    return components;
  }

  @Override
  public Collection<Class<? extends LifecycleComponent>> getGuiceServiceClasses() {
    final List<Class<? extends LifecycleComponent>> services = new ArrayList<>(1);
    services.add(TransportServiceInterceptor.class);
    return services;
  }

  @Override
  public List<Class<? extends ActionFilter>> getActionFilters() {
    return Collections.singletonList(IndexLevelActionFilter.class);
  }

  @Override
  public void onIndexModule(IndexModule indexModule) {
    indexModule.setSearcherWrapper(RoleIndexSearcherWrapper::new);
  }

  @Override
  public List<Setting<?>> getSettings() {
    return ImmutableList.of(
        Setting.groupSetting("readonlyrest.", Setting.Property.Dynamic, Setting.Property.NodeScope)
    );
  }

  @Override
  public Map<String, Supplier<Transport>> getTransports(Settings settings,
                                                        ThreadPool threadPool,
                                                        BigArrays bigArrays,
                                                        CircuitBreakerService circuitBreakerService,
                                                        NamedWriteableRegistry namedWriteableRegistry,
                                                        NetworkService networkService) {
    if(sslConfig.interNodeSsl().isDefined()) {
      return Collections.singletonMap("ror_ssl_internode", () ->
              new SSLNetty4InternodeServerTransport(
                      settings,
                      threadPool,
                      networkService,
                      bigArrays,
                      namedWriteableRegistry,
                      circuitBreakerService,
                      sslConfig.interNodeSsl().get())
      );
    } else {
      return Collections.EMPTY_MAP;
    }
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
    if(sslConfig.externalSsl().isDefined()) {
      return Collections.singletonMap(
          "ssl_netty4", () ->
              new SSLNetty4HttpServerTransport(
                  settings, networkService, bigArrays, threadPool, xContentRegistry, dispatcher, sslConfig.externalSsl().get()
              ));
    } else {
      return Collections.EMPTY_MAP;
    }
  }

  @Override
  @SuppressWarnings({ "unchecked", "rawtypes" })
  public List<ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {
    return Collections.singletonList(
        new ActionHandler(RRAdminAction.instance(), TransportRRAdminAction.class));
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
      ThreadRepo.setRestChannel(channel);
      restHandler.handleRequest(request, channel, client);
    };
  }
}
