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

package tech.beshu.ror.es.rradmin;

import monix.execution.Scheduler;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import tech.beshu.ror.adminapi.AdminRestApi;
import tech.beshu.ror.boot.SchedulerPools$;
import tech.beshu.ror.configuration.FileConfigLoader;
import tech.beshu.ror.configuration.IndexConfigManager;
import tech.beshu.ror.es.EsIndexJsonContentProvider;
import tech.beshu.ror.es.RorInstanceSupplier;
import tech.beshu.ror.utils.OsEnvVarsProvider$;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Optional;

public class TransportRRAdminAction extends HandledTransportAction<RRAdminRequest, RRAdminResponse> {

  private final Scheduler adminRestApiScheduler = SchedulerPools$.MODULE$.adminRestApiScheduler();
  private final IndexConfigManager indexConfigManager;
  private final FileConfigLoader fileConfigLoader;

  @Inject
  public TransportRRAdminAction(Settings settings, ThreadPool threadPool, TransportService transportService,
      ActionFilters actionFilters, IndexNameExpressionResolver indexNameExpressionResolver, Environment env,
      EsIndexJsonContentProvider indexContentProvider) {
    super(settings, RRAdminAction.NAME, threadPool, transportService, actionFilters, indexNameExpressionResolver,
          RRAdminRequest::new
    );
    this.indexConfigManager = new IndexConfigManager(indexContentProvider);
    this.fileConfigLoader = new FileConfigLoader(env.configFile(), OsEnvVarsProvider$.MODULE$);
  }

  @Override
  protected void doExecute(RRAdminRequest request, ActionListener<RRAdminResponse> listener) {
    Optional<AdminRestApi> api = getApi();
    if(api.isPresent()) {
      AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
        api.get()
            .call(request.getAdminRequest())
            .runAsync(
                response -> {
                  listener.onResponse(new RRAdminResponse(response));
                  return null;
                },
                adminRestApiScheduler
            );
        return null;
      });
    } else {
      listener.onResponse(new RRAdminResponse(AdminRestApi.AdminResponse$.MODULE$.notAvailable()));
    }
  }

  private Optional<AdminRestApi> getApi() {
    return RorInstanceSupplier.getInstance().get().map(instance -> new AdminRestApi(instance, indexConfigManager, fileConfigLoader));
  }

}
