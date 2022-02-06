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

import org.elasticsearch.action.{ActionRequest, ActionRequestValidationException}
import org.elasticsearch.rest.RestRequest
import org.elasticsearch.rest.RestRequest.Method.{DELETE, GET, POST}
import tech.beshu.ror.api.TestSettingsApi
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.{Constants, RequestId}

class RRTestSettingsRequest(testSettingsApiRequest: TestSettingsApi.TestSettingsRequest,
                            esRestRequest: RestRequest) extends ActionRequest {

  val getTestSettingsRequest: TestSettingsApi.TestSettingsRequest = testSettingsApiRequest
  lazy val requestContextId: RequestId = RequestId(s"${esRestRequest.hashCode()}-${this.hashCode()}")

  override def validate(): ActionRequestValidationException = null
}

object RRTestSettingsRequest {

  def createFrom(request: RestRequest): RRTestSettingsRequest = {
    val requestType = (request.uri().addTrailingSlashIfNotPresent(), request.method()) match {
      case (Constants.PROVIDE_TEST_SETTINGS_PATH, GET) =>
        TestSettingsApi.TestSettingsRequest.Type.ProvideTestSettings
      case (Constants.DELETE_TEST_SETTINGS_PATH, DELETE) =>
        TestSettingsApi.TestSettingsRequest.Type.InvalidateTestSettings
      case (Constants.UPDATE_TEST_SETTINGS_PATH, POST) =>
        TestSettingsApi.TestSettingsRequest.Type.UpdateTestSettings
      case (Constants.PROVIDE_LOCAL_USERS_PATH, GET) =>
        TestSettingsApi.TestSettingsRequest.Type.ProvideLocalUsers
      case (unknownUri, unknownMethod) =>
        throw new IllegalStateException(s"Unknown request: $unknownMethod $unknownUri")
    }
    new RRTestSettingsRequest(
      new TestSettingsApi.TestSettingsRequest(
        requestType,
        request.content.utf8ToString
      ),
      request
    )
  }
}

