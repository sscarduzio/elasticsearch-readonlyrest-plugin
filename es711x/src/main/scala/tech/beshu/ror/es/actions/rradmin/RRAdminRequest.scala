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

import cats.data.NonEmptyList
import org.elasticsearch.action.{ActionRequest, ActionRequestValidationException}
import org.elasticsearch.rest.RestRequest
import org.elasticsearch.rest.RestRequest.Method.{DELETE, GET, POST}
import tech.beshu.ror.{Constants, RequestId}
import tech.beshu.ror.adminapi.AdminRestApi
import tech.beshu.ror.utils.ScalaOps._

import scala.collection.JavaConverters._

class RRAdminRequest(adminApiRequest: AdminRestApi.AdminRequest,
                     esRestRequest: RestRequest) extends ActionRequest {

  val getAdminRequest: AdminRestApi.AdminRequest = adminApiRequest
  lazy val requestContextId: RequestId = RequestId(s"${esRestRequest.hashCode()}-${this.hashCode()}")

  override def validate(): ActionRequestValidationException = null
}

object RRAdminRequest {

  def createFrom(request: RestRequest): RRAdminRequest = {
    val requestType = (request.uri().addTrailingSlashIfNotPresent(), request.method()) match {
      case (Constants.FORCE_RELOAD_CONFIG_PATH, POST) =>
        AdminRestApi.AdminRequest.Type.ForceReload
      case (Constants.PROVIDE_FILE_CONFIG_PATH, GET) =>
        AdminRestApi.AdminRequest.Type.ProvideFileConfig
      case (Constants.PROVIDE_INDEX_CONFIG_PATH, GET) =>
        AdminRestApi.AdminRequest.Type.ProvideIndexConfig
      case (Constants.UPDATE_INDEX_CONFIG_PATH, POST) =>
        AdminRestApi.AdminRequest.Type.UpdateIndexConfig
      case (Constants.UPDATE_TEST_CONFIG_PATH, POST) =>
        AdminRestApi.AdminRequest.Type.UpdateTestConfig
      case (Constants.DELETE_TEST_CONFIG_PATH, DELETE) =>
        AdminRestApi.AdminRequest.Type.InvalidateTestConfig
      case (unknownUri, unknownMethod) =>
        throw new IllegalStateException(s"Unknown request: $unknownMethod $unknownUri")
    }
    new RRAdminRequest(
      new AdminRestApi.AdminRequest(
        requestType,
        request.method.name,
        request.path,
        request
          .getHeaders.asScala
          .flatMap { case (name, values) =>
            NonEmptyList
              .fromList(values.asScala.toList)
              .map((name, _))
          }
          .toMap,
        request.content.utf8ToString
      ),
      request
    )
  }
}