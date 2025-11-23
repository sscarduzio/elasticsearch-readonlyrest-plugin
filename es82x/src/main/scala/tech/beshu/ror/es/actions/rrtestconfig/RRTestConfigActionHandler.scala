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
package tech.beshu.ror.es.actions.rrtestconfig

import cats.implicits.toShow
import monix.execution.Scheduler
import tech.beshu.ror.utils.RequestIdAwareLogging
import org.elasticsearch.action.ActionListener
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.api.TestConfigApi.TestConfigResponse
import tech.beshu.ror.boot.RorSchedulers
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged
import tech.beshu.ror.utils.RorInstanceSupplier

class RRTestConfigActionHandler extends RequestIdAwareLogging {

  private implicit val rorRestApiScheduler: Scheduler = RorSchedulers.restApiScheduler

  def handle(request: RRTestConfigRequest, listener: ActionListener[RRTestConfigResponse]): Unit = {
    getApi match {
      case Some(api) => doPrivileged {
        implicit val requestId: RequestId = request.requestContextId
        api
          .call(request.getTestConfigRequest)
          .runAsync { result =>
            handle(result, listener)
          }
      }
      case None =>
        listener.onFailure(new Exception("TestConfig API is not available"))
    }
  }

  private def handle(result: Either[Throwable, TestConfigResponse],
                     listener: ActionListener[RRTestConfigResponse])
                    (implicit requestId: RequestId): Unit = result match {
    case Right(response) =>
      listener.onResponse(new RRTestConfigResponse(response))
    case Left(ex) =>
      logger.error(s"[${requestId.show}] RRTestConfig internal error", ex)
      listener.onFailure(new Exception(ex))
  }

  private def getApi =
    RorInstanceSupplier.get().map(_.testConfigApi)
}
