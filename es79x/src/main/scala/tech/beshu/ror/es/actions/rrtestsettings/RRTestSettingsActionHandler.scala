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
package tech.beshu.ror.es.actions.rrtestsettings

import monix.execution.Scheduler
import org.elasticsearch.action.ActionListener
import tech.beshu.ror.RequestId
import tech.beshu.ror.api.TestSettingsApi.TestSettingsResponse
import tech.beshu.ror.boot.RorSchedulers
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged
import tech.beshu.ror.utils.RorInstanceSupplier

import scala.language.postfixOps

class RRTestSettingsActionHandler() {

  private implicit val rorRestApiScheduler: Scheduler = RorSchedulers.restApiScheduler

  def handle(request: RRTestSettingsRequest, listener: ActionListener[RRTestSettingsResponse]): Unit = {
    getApi match {
      case Some(api) => doPrivileged {
        implicit val requestId: RequestId = request.requestContextId
        api
          .call(request.getTestSettingsRequest)
          .runAsync { response =>
            listener.onResponse(RRTestSettingsResponse(response))
          }
      }
      case None =>
        listener.onResponse(new RRTestSettingsResponse(TestSettingsResponse.notAvailable))
    }
  }

  private def getApi =
    RorInstanceSupplier.get().map(_.testSettingsApi)
}
