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

import org.elasticsearch.action.{ActionRequest, ActionRequestValidationException}
import org.elasticsearch.rest.RestRequest
import org.elasticsearch.rest.RestRequest.Method.{DELETE, GET, POST}
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.api.{RorApiRequest, TestSettingsApi}
import tech.beshu.ror.constants
import tech.beshu.ror.es.actions.RorActionRequest
import tech.beshu.ror.utils.ScalaOps.*

class RRTestConfigRequest(testConfigApiRequest: TestSettingsApi.TestSettingsRequest,
                          esRestRequest: RestRequest) extends ActionRequest with RorActionRequest {

  def getTestConfigRequest: RorApiRequest[TestSettingsApi.TestSettingsRequest] =
    RorApiRequest(testConfigApiRequest, loggerUser)

  lazy val requestContextId: RequestId = RequestId(s"${esRestRequest.hashCode()}-${this.hashCode()}")

  override def validate(): ActionRequestValidationException = null
}

object RRTestConfigRequest {

  def createFrom(request: RestRequest): RRTestConfigRequest = {
    val requestType = (request.uri().addTrailingSlashIfNotPresent(), request.method()) match {
      case (constants.PROVIDE_TEST_CONFIG_PATH, GET) =>
        TestSettingsApi.TestSettingsRequest.Type.ProvideTestSettings
      case (constants.DELETE_TEST_CONFIG_PATH, DELETE) =>
        TestSettingsApi.TestSettingsRequest.Type.InvalidateTestSettings
      case (constants.UPDATE_TEST_CONFIG_PATH, POST) =>
        TestSettingsApi.TestSettingsRequest.Type.UpdateTestSettings
      case (constants.PROVIDE_LOCAL_USERS_PATH, GET) =>
        TestSettingsApi.TestSettingsRequest.Type.ProvideLocalUsers
      case (unknownUri, unknownMethod) =>
        throw new IllegalStateException(s"Unknown request: $unknownMethod $unknownUri")
    }
    new RRTestConfigRequest(
      TestSettingsApi.TestSettingsRequest(requestType, request.content.utf8ToString),
      request
    )
  }
}

