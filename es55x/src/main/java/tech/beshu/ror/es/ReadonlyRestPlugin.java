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
import org.elasticsearch.action.search.SearchTransportService;
import org.elasticsearch.action.support.ActionFilter;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
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
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.RemoteClusterService;
import scala.concurrent.duration.FiniteDuration;
import tech.beshu.ror.Constants;
import tech.beshu.ror.configuration.RorSsl;
import tech.beshu.ror.configuration.RorSsl$;
import tech.beshu.ror.es.rradmin.RRAdminAction;
import tech.beshu.ror.es.rradmin.TransportRRAdminAction;
import tech.beshu.ror.es.rradmin.rest.RestRRAdminAction;
import tech.beshu.ror.es.security.RoleIndexSearcherWrapper;
import tech.beshu.ror.utils.ScalaJavaHelper$;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

public class ReadonlyRestPlugin extends Plugin
    implements ScriptPlugin, ActionPlugin, IngestPlugin, NetworkPlugin {

  private final RorSsl sslConfig;

  @Inject
  private IndexLevelActionFilter ilaf;

  public ReadonlyRestPlugin(Settings s) {
    Environment environment = new Environment(s);
    Constants.FIELDS_ALWAYS_ALLOW.addAll(Sets.newHashSet(MapperService.getAllMetaFields()));
    FiniteDuration timeout = FiniteDuration.apply(10, TimeUnit.SECONDS);
    this.sslConfig = RorSsl$.MODULE$.load(environment.configFile())
        .map(result -> ScalaJavaHelper$.MODULE$.getOrElse(result, error -> new ElasticsearchException(error.message())))
        .runSyncUnsafe(timeout, Scheduler$.MODULE$.global(), CanBlock$.MODULE$.permit());
  }

  @Override
  public void close() {
    ilaf.stop();
  }

  @Override
  public List<Class<? extends ActionFilter>> getActionFilters() {
    return Collections.singletonList(IndexLevelActionFilter.class);
  }

  @Override
  public Collection<Class<? extends LifecycleComponent>> getGuiceServiceClasses() {
    final List<Class<? extends LifecycleComponent>> services = new ArrayList<>(1);
    services.add(TransportServiceInterceptor.class);
    return services;
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
              new SSLTransportNetty4(
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
      restHandler.handleRequest(RorRestRequest.from(request), channel, client);
    };
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

  public static class TransportServiceInterceptor extends AbstractLifecycleComponent {

    private static RemoteClusterServiceSupplier remoteClusterServiceSupplier;

    @Inject
    public TransportServiceInterceptor(Settings settings, final SearchTransportService transportService) {
      super(settings);
      Optional.ofNullable(transportService.getRemoteClusterService()).ifPresent(r -> getRemoteClusterServiceSupplier().update(r));
    }

    synchronized public static RemoteClusterServiceSupplier getRemoteClusterServiceSupplier() {
      if (remoteClusterServiceSupplier == null) {
        remoteClusterServiceSupplier = new RemoteClusterServiceSupplier();
      }
      return remoteClusterServiceSupplier;
    }

    @Override
    protected void doStart() { /* unused */ }

    @Override
    protected void doStop() {  /* unused */ }

    @Override
    protected void doClose() {  /* unused */ }
  }

  private static class RemoteClusterServiceSupplier implements Supplier<Optional<RemoteClusterService>> {

    private final AtomicReference<Optional<RemoteClusterService>> remoteClusterServiceAtomicReference = new AtomicReference(Optional.empty());

    @Override
    public Optional<RemoteClusterService> get() {
      return remoteClusterServiceAtomicReference.get();
    }

    private void update(RemoteClusterService service) {
      remoteClusterServiceAtomicReference.set(Optional.ofNullable(service));
    }
  }

  private static class RorRestRequest extends RestRequest {

    private final RestRequest underlying;

    private RorRestRequest(RestRequest restRequest, Map<String, String> params) {
      super(restRequest.getXContentRegistry(), params, restRequest.path(), restRequest.getHeaders());
      this.underlying = restRequest;
    }

    static RorRestRequest from(RestRequest restRequest) {
      Map<String, String> params = restRequest.params();
      params.put("error_trace", "true");
      RorRestRequest rorRestRequest = new RorRestRequest(restRequest, params);
      rorRestRequest.param("error_trace"); // hack! we're faking that user used this param in request query
      return rorRestRequest;
    }

    @Override
    public Method method() {
      return underlying.method();
    }

    @Override
    public String uri() {
      return underlying.uri();
    }

    @Override
    public boolean hasContent() {
      return underlying.hasContent();
    }

    @Override
    public BytesReference content() {
      return underlying.content();
    }
  }
}
