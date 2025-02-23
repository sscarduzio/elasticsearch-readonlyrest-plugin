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
package tech.beshu.ror.es.actions.rrauthmock

import cats.implicits.toShow
import monix.execution.Scheduler
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.ActionListener
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.api.AuthMockApi.AuthMockResponse
import tech.beshu.ror.boot.RorSchedulers
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged
import tech.beshu.ror.utils.RorInstanceSupplier
import tech.beshu.ror.implicits.*

class RRAuthMockActionHandler extends Logging {

  private implicit val rorRestApiScheduler: Scheduler = RorSchedulers.restApiScheduler

  def handle(request: RRAuthMockRequest, listener: ActionListener[RRAuthMockResponse]): Unit = {
    getApi match {
      case Some(api) => doPrivileged {
        implicit val requestId: RequestId = request.requestContextId
        api
          .call(request.getAuthMockRequest)
          .runAsync { response =>
            handle(response, listener)
          }
      }
      case None =>
        listener.onFailure(new Exception("AuthMock API is not available"))
    }
  }

  private def handle(result: Either[Throwable, AuthMockResponse],
                     listener: ActionListener[RRAuthMockResponse])
                    (implicit requestId: RequestId): Unit = {
    result match {
      case Right(response) =>
        listener.onResponse(new RRAuthMockResponse(response))
      case Left(ex) =>
        logger.error(s"[${requestId.show}] RRAuthMock internal error", ex)
        listener.onFailure(new Exception(ex))
    }
  }

  private def getApi =
    RorInstanceSupplier.get().map(_.authMockApi)
}
