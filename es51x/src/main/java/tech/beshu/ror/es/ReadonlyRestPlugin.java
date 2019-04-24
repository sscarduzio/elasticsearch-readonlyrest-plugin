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
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.support.ActionFilter;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Setting;
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
import tech.beshu.ror.Constants;
import tech.beshu.ror.configuration.AllowedSettings;
import tech.beshu.ror.es.rradmin.RRAdminAction;
import tech.beshu.ror.es.rradmin.TransportRRAdminAction;
import tech.beshu.ror.es.rradmin.rest.RestRRAdminAction;
import tech.beshu.ror.es.security.RoleIndexSearcherWrapper;
import tech.beshu.ror.settings.BasicSettings;
import tech.beshu.ror.shims.es.LoggerShim;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class ReadonlyRestPlugin extends Plugin
    implements ScriptPlugin, ActionPlugin, IngestPlugin, NetworkPlugin {

  private final BasicSettings basicSettings;
  private final LoggerShim logger;
  private Settings settings;
  private Environment environment;

  public ReadonlyRestPlugin(Settings s) {
    this.settings = s;
    this.environment = new Environment(s);
    Constants.FIELDS_ALWAYS_ALLOW.addAll(Sets.newHashSet(MapperService.getAllMetaFields()));
    this.logger = ESContextImpl.mkLoggerShim(Loggers.getLogger(getClass()));
    this.basicSettings = BasicSettings.fromFileObj(logger, this.environment.configFile().toAbsolutePath(), settings);
  }

  @Override
  public void close() {
    ESContextImpl.shutDownObservable.shutDown();
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

    if (!basicSettings.getSslHttpSettings().map(x -> x.isSSLEnabled()).orElse(false)) {
      return Collections.EMPTY_MAP;
    }

    AtomicReference<Map<String, Supplier<HttpServerTransport>>> result = new AtomicReference<>();
    AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
      result.set(Collections.singletonMap(
          "ssl_netty4", () ->
              new SSLTransportNetty4(settings, networkService, bigArrays, threadPool, basicSettings.getSslHttpSettings().get())));
      return null;
    });

    return result.get();
  }

  @Override
  public List<Setting<?>> getSettings() {
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
  public List<Class<? extends RestHandler>> getRestHandlers() {
    return Lists.newArrayList(ReadonlyRestAction.class, RestRRAdminAction.class);
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