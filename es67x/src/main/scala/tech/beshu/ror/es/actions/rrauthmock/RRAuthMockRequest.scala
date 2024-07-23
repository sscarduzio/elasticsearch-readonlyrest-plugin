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

import org.elasticsearch.action.{ActionRequest, ActionRequestValidationException}
import org.elasticsearch.rest.RestRequest
import org.elasticsearch.rest.RestRequest.Method.{GET, POST}
import tech.beshu.ror.api.AuthMockApi
import tech.beshu.ror.es.actions.RorActionRequest
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.{constants, RequestId}

class RRAuthMockRequest(authMockApiRequest: AuthMockApi.AuthMockRequest,
                        esRestRequest: RestRequest) extends ActionRequest with RorActionRequest {

  def this() = {
    this(null, null)
  }

  val getAuthMockRequest: AuthMockApi.AuthMockRequest = authMockApiRequest
  lazy val requestContextId: RequestId = RequestId(s"${esRestRequest.hashCode()}-${this.hashCode()}")

  override def validate(): ActionRequestValidationException = null
}

object RRAuthMockRequest {

  def createFrom(request: RestRequest): RRAuthMockRequest = {
    val requestType = (request.uri().addTrailingSlashIfNotPresent(), request.method()) match {
      case (constants.PROVIDE_AUTH_MOCK_PATH, GET) =>
        AuthMockApi.AuthMockRequest.Type.ProvideAuthMock
      case (constants.CONFIGURE_AUTH_MOCK_PATH, POST) =>
        AuthMockApi.AuthMockRequest.Type.UpdateAuthMock
      case (unknownUri, unknownMethod) =>
        throw new IllegalStateException(s"Unknown request: $unknownMethod $unknownUri")
    }
    new RRAuthMockRequest(
      new AuthMockApi.AuthMockRequest(
        requestType,
        request.content.utf8ToString
      ),
      request
    )
  }
}