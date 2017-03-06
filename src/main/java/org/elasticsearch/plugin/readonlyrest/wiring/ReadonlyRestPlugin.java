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

import org.elasticsearch.action.support.ActionFilter;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.http.HttpServerTransport;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.plugin.readonlyrest.ConfigurationHelper;
import org.elasticsearch.plugin.readonlyrest.IndexLevelActionFilter;
import org.elasticsearch.plugin.readonlyrest.SSLTransportNetty4;
import org.elasticsearch.plugins.ActionPlugin;
import org.elasticsearch.plugins.IngestPlugin;
import org.elasticsearch.plugins.NetworkPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.ScriptPlugin;
import org.elasticsearch.rest.RestHandler;
import org.elasticsearch.threadpool.ThreadPool;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class ReadonlyRestPlugin extends Plugin implements ScriptPlugin, ActionPlugin, IngestPlugin, NetworkPlugin {

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
    return Collections.singletonMap("ssl_netty4", () -> new SSLTransportNetty4(settings, networkService, bigArrays, threadPool));
  }

  @Override
  public List<Class<? extends RestHandler>> getRestHandlers() {
    return Collections.singletonList(ReadonlyRestRestAction.class);
  }

  @Override
  public List<Setting<?>> getSettings() {
    return ConfigurationHelper.allowedSettings();
  }

}
