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
import tech.beshu.ror.api.{RorApiRequest, TestConfigApi}
import tech.beshu.ror.constants
import tech.beshu.ror.es.actions.RorActionRequest
import tech.beshu.ror.utils.ScalaOps.*

class RRTestConfigRequest(testConfigApiRequest: TestConfigApi.TestConfigRequest,
                          esRestRequest: RestRequest) extends ActionRequest with RorActionRequest {

  def getTestConfigRequest: RorApiRequest[TestConfigApi.TestConfigRequest] =
    RorApiRequest(testConfigApiRequest, loggerUser)

  lazy val requestContextId: RequestId = RequestId(s"${esRestRequest.hashCode()}-${this.hashCode()}")

  override def validate(): ActionRequestValidationException = null
}

object RRTestConfigRequest {

  def createFrom(request: RestRequest): RRTestConfigRequest = {
    val requestType = (request.uri().addTrailingSlashIfNotPresent(), request.method()) match {
      case (constants.PROVIDE_TEST_CONFIG_PATH, GET) =>
        TestConfigApi.TestConfigRequest.Type.ProvideTestConfig
      case (constants.DELETE_TEST_CONFIG_PATH, DELETE) =>
        TestConfigApi.TestConfigRequest.Type.InvalidateTestConfig
      case (constants.UPDATE_TEST_CONFIG_PATH, POST) =>
        TestConfigApi.TestConfigRequest.Type.UpdateTestConfig
      case (constants.PROVIDE_LOCAL_USERS_PATH, GET) =>
        TestConfigApi.TestConfigRequest.Type.ProvideLocalUsers
      case (unknownUri, unknownMethod) =>
        throw new IllegalStateException(s"Unknown request: $unknownMethod $unknownUri")
    }
    new RRTestConfigRequest(
      TestConfigApi.TestConfigRequest(requestType, request.content.utf8ToString),
      request
    )
  }
}

