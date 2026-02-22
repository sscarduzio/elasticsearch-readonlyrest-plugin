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

import org.elasticsearch.action.{ActionRequest, ActionRequestValidationException}
import org.elasticsearch.rest.RestRequest
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.api.MainSettingsApi
import tech.beshu.ror.es.actions.RorActionRequest
import tech.beshu.ror.utils.ScalaOps.*
import tech.beshu.ror.api.MainSettingsApi.MainSettingsRequest
import tech.beshu.ror.accesscontrol.request.RequestContext.Method

class RRAdminRequest(adminApiRequest: MainSettingsApi.MainSettingsRequest,
                     esRestRequest: RestRequest) extends ActionRequest with RorActionRequest {

  val getAdminRequest: MainSettingsApi.MainSettingsRequest = adminApiRequest
  lazy val requestContextId: RequestId = RequestId(s"${esRestRequest.hashCode()}-${this.hashCode()}")

  def this() = {
    this(null, null)
  }

  override def validate(): ActionRequestValidationException = null
}

object RRAdminRequest {

  def createFrom(request: RestRequest): RRAdminRequest = {
    val requestType = MainSettingsRequest.from(request.uri(), Method.fromStringUnsafe(request.method().name()))
    new RRAdminRequest(
      new MainSettingsApi.MainSettingsRequest(requestType, request.content.utf8ToString),
      request
    )
  }
}