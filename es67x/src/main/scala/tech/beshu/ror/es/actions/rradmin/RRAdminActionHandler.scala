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
package tech.beshu.ror.es.actions.rradmin

import java.nio.file.Path

import monix.execution.Scheduler
import monix.execution.schedulers.CanBlock
import org.elasticsearch.ElasticsearchException
import org.elasticsearch.action.ActionListener
import tech.beshu.ror.RequestId
import tech.beshu.ror.adminapi.AdminRestApi
import tech.beshu.ror.boot.RorSchedulers
import tech.beshu.ror.configuration.loader.FileConfigLoader
import tech.beshu.ror.configuration.{IndexConfigManager, RorIndexNameConfiguration}
import tech.beshu.ror.es.IndexJsonContentService
import tech.beshu.ror.providers.JvmPropertiesProvider
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged
import tech.beshu.ror.utils.RorInstanceSupplier

import scala.concurrent.duration._
import scala.language.postfixOps

class RRAdminActionHandler(indexContentProvider: IndexJsonContentService,
                           esConfigFile: Path) {

  private implicit val adminRestApiScheduler: Scheduler = RorSchedulers.adminRestApiScheduler

  private val rorIndexNameConfig = RorIndexNameConfiguration
    .load(esConfigFile)
    .map(_.fold(e => throw new ElasticsearchException(e.message), identity))
    .runSyncUnsafe(10 seconds)(adminRestApiScheduler, CanBlock.permit)

  private val indexConfigManager = new IndexConfigManager(indexContentProvider)
  private val fileConfigLoader = new FileConfigLoader(esConfigFile, JvmPropertiesProvider)

  def handle(request: RRAdminRequest, listener: ActionListener[RRAdminResponse]): Unit = {
    getApi match {
      case Some(api) => doPrivileged {
        implicit val requestId: RequestId = request.requestContextId
        api
          .call(request.getAdminRequest)
          .runAsync { response =>
            listener.onResponse(RRAdminResponse(response))
          }
      }
      case None =>
        listener.onResponse(new RRAdminResponse(AdminRestApi.AdminResponse.notAvailable))
    }
  }

  private def getApi =
    RorInstanceSupplier.get()
      .map(instance => new AdminRestApi(instance, indexConfigManager, fileConfigLoader, rorIndexNameConfig.index))
}
