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

import org.elasticsearch.action.ActionModule;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.component.LifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.http.HttpServerModule;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestFilter;
import org.elasticsearch.rest.RestFilterChain;
import org.elasticsearch.rest.RestRequest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;


public class ReadonlyRestPlugin extends Plugin {
  static CompletableFuture<NodeClient> clientFuture = new CompletableFuture<>();
  private final Settings settings;

  @Inject
  ReadonlyRestPlugin(Settings settings) {
    this.settings = settings;
  }

  @Override
  public Collection<Class<? extends LifecycleComponent>> nodeServices() {
    List l = new ArrayList(1);
    l.add(new AbstractLifecycleComponent(settings, getClass()) {
      @Override
      protected void doStart() {

      }

      @Override
      protected void doStop() {
        ESContextImpl.shutDownObservable.shutDown();
      }

      @Override
      protected void doClose() {

      }
    });
    return l;
  }

  @Override
  public String name() {
    return "readonlyrest";
  }

  @Override
  public String description() {
    return "Access control for Elasticsearch";
  }

  public void onModule(HttpServerModule module) {
    module.setHttpServerTransport(SSLTransport.class, this.getClass().getSimpleName());
  }

  public void onModule(final ActionModule module) {
    module.registerFilter(IndexLevelActionFilter.class);
  }

  @Override
  public Collection<Module> nodeModules() {
    final Module restLoggerModule = binder -> binder.bind(RestLogger.class).asEagerSingleton();
    return Collections.singleton(restLoggerModule);
  }


  public static class RestLogger {
    @Inject
    public RestLogger(RestController restController, NodeClient client) {

      clientFuture.complete(client);

      restController.registerFilter(new RestFilter() {
        @Override
        public void process(RestRequest request, RestChannel channel, RestFilterChain filterChain) throws Exception {
          ThreadRepo.channel.set(channel);
          filterChain.continueProcessing(request, channel);
        }
      });
    }
  }
  //  @Override
//  @SuppressWarnings({"unchecked", "rawtypes"})
//  public List<ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {
//    return Collections.singletonList(
//      new ActionHandler(RRAdminAction.INSTANCE, TransportRRAdminAction.class, new Class[0]));
//  }
//
//  @Override
//  @SuppressWarnings({"unchecked", "rawtypes"})
//  public List<RestHandler> getRestHandlers(
//    Settings settings, RestController restController, ClusterSettings clusterSettings,
//    IndexScopedSettings indexScopedSettings, SettingsFilter settingsFilter,
//    IndexNameExpressionResolver indexNameExpressionResolver, Supplier<DiscoveryNodes> nodesInCluster) {
//    return Collections.singletonList(new RestRRAdminAction(settings, restController));
//  }

}
