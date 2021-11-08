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

import monix.execution.Scheduler
import org.elasticsearch.action.ActionListener
import tech.beshu.ror.RequestId
import tech.beshu.ror.api.ConfigApi
import tech.beshu.ror.boot.RorSchedulers
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged
import tech.beshu.ror.utils.RorInstanceSupplier

import scala.language.postfixOps

class RRAdminActionHandler() {

  private implicit val adminRestApiScheduler: Scheduler = RorSchedulers.restApiScheduler

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
        listener.onResponse(new RRAdminResponse(ConfigApi.ConfigResponse.notAvailable))
    }
  }

  private def getApi =
    RorInstanceSupplier.get().map(_.configApi)
}
