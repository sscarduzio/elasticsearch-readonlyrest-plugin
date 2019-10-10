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
package tech.beshu.ror.es.rradmin

import monix.execution.Scheduler
import org.elasticsearch.action.ActionListener
import org.elasticsearch.action.support.{ActionFilters, HandledTransportAction}
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.common.io.stream.Writeable
import org.elasticsearch.env.Environment
import org.elasticsearch.tasks.Task
import org.elasticsearch.transport.TransportService
import tech.beshu.ror.adminapi.AdminRestApi
import tech.beshu.ror.boot.SchedulerPools
import tech.beshu.ror.configuration.{FileConfigLoader, IndexConfigManager}
import tech.beshu.ror.es.RorInstanceSupplier
import tech.beshu.ror.es.providers.EsIndexJsonContentProvider
import tech.beshu.ror.es.utils.AccessControllerHelper.doPrivileged
import tech.beshu.ror.providers.JvmPropertiesProvider

class TransportRRAdminAction(transportService: TransportService,
                             actionFilters: ActionFilters,
                             env: Environment,
                             indexContentProvider: EsIndexJsonContentProvider,
                             requestReader: Writeable.Reader[RRAdminRequest],
                             ignore: Unit) // hack!
  extends HandledTransportAction[RRAdminRequest, RRAdminResponse](
    RRAdminAction.name, transportService, actionFilters, requestReader) {

  @Inject
  def this(transportService: TransportService,
           actionFilters: ActionFilters,
           env: Environment,
           indexContentProvider: EsIndexJsonContentProvider,
           requestReader: Writeable.Reader[RRAdminRequest]) {
    this(transportService, actionFilters, env, indexContentProvider, requestReader, ())
  }

  private implicit val adminRestApiScheduler: Scheduler = SchedulerPools.adminRestApiScheduler
  private val indexConfigManager = new IndexConfigManager(indexContentProvider)
  private val fileConfigLoader = new FileConfigLoader(env.configFile(), JvmPropertiesProvider)

  override def doExecute(task: Task, request: RRAdminRequest, listener: ActionListener[RRAdminResponse]): Unit = {
    getApi match {
      case Some(api) => doPrivileged {
        api
          .call(request.getAdminRequest)
          .runAsync { response =>
            listener.onResponse(new RRAdminResponse(response))
          }
      }
      case None =>
        listener.onResponse(new RRAdminResponse(AdminRestApi.AdminResponse.notAvailable))
    }
  }

  private def getApi =
    RorInstanceSupplier.get()
      .map(instance => new AdminRestApi(instance, indexConfigManager, fileConfigLoader))
}
