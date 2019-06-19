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

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import monix.execution.Scheduler$;
import monix.execution.schedulers.CanBlock$;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.support.ActionFilter;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.BigArrays;
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
import org.elasticsearch.rest.RestHandler;
import org.elasticsearch.threadpool.ThreadPool;
import scala.concurrent.duration.FiniteDuration;
import tech.beshu.ror.Constants;
import tech.beshu.ror.configuration.RorSsl;
import tech.beshu.ror.configuration.RorSsl$;
import tech.beshu.ror.es.rradmin.RRAdminAction;
import tech.beshu.ror.es.rradmin.TransportRRAdminAction;
import tech.beshu.ror.es.rradmin.rest.RestRRAdminAction;
import tech.beshu.ror.es.security.RoleIndexSearcherWrapper;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class ReadonlyRestPlugin extends Plugin
    implements ScriptPlugin, ActionPlugin, IngestPlugin, NetworkPlugin {

  private final RorSsl sslConfig;

  @Inject
  private IndexLevelActionFilter ilaf;
  private Environment environment;

  public ReadonlyRestPlugin(Settings s) {
    this.environment = new Environment(s);
    Constants.FIELDS_ALWAYS_ALLOW.addAll(Sets.newHashSet(MapperService.getAllMetaFields()));
    FiniteDuration timeout = FiniteDuration.apply(10, TimeUnit.SECONDS);
    this.sslConfig = RorSsl$.MODULE$.load(environment.configFile())
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
  public Map<String, Supplier<HttpServerTransport>> getHttpTransports(
      Settings settings,
      ThreadPool threadPool,
      BigArrays bigArrays,
      CircuitBreakerService circuitBreakerService,
      NamedWriteableRegistry namedWriteableRegistry,
      NetworkService networkService
  ) {
    if(sslConfig.externalSsl().isDefined()) {
      return Collections.singletonMap(
          "ssl_netty4", () ->
              new SSLTransportNetty4(
                  settings, networkService, bigArrays, threadPool, sslConfig.externalSsl().get()
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
  public List<Class<? extends RestHandler>> getRestHandlers() {
    return Lists.newArrayList(ReadonlyRestAction.class, RestRRAdminAction.class);
  }

  @Override
  public void onIndexModule(IndexModule module) {
    module.setSearcherWrapper(indexService -> {
      try {
        return new RoleIndexSearcherWrapper(indexService);
      } catch (Exception e) {
        e.printStackTrace();
      }
      return null;
    });
  }
}