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

import cats.implicits.toShow
import monix.execution.Scheduler
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.ActionListener
import tech.beshu.ror.RequestId
import tech.beshu.ror.api.ConfigApi.ConfigResponse
import tech.beshu.ror.boot.RorSchedulers
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged
import tech.beshu.ror.utils.RorInstanceSupplier

import scala.language.postfixOps

class RRAdminActionHandler() extends Logging {

  private implicit val adminRestApiScheduler: Scheduler = RorSchedulers.restApiScheduler

  def handle(request: RRAdminRequest, listener: ActionListener[RRAdminResponse]): Unit = {
    getApi match {
      case Some(api) => doPrivileged {
        implicit val requestId: RequestId = request.requestContextId
        api
          .call(request.getAdminRequest)
          .runAsync { response =>
            handle(response, listener)
          }
      }
      case None =>
        listener.onFailure(new Exception("Config API is not available"))
    }
  }

  private def handle(result: Either[Throwable, ConfigResponse],
                     listener: ActionListener[RRAdminResponse])
                    (implicit requestId: RequestId): Unit = result match {
    case Right(response) =>
      listener.onResponse(new RRAdminResponse(response))
    case Left(ex) =>
      logger.error(s"[${requestId.show}] RRAdminAction internal error", ex)
      listener.onFailure(new Exception(ex))
  }

  private def getApi =
    RorInstanceSupplier.get().map(_.configApi)
}
