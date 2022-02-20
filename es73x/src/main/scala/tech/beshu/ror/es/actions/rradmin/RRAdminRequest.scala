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
import org.elasticsearch.rest.RestRequest.Method.{GET, POST}
import tech.beshu.ror.api.ConfigApi
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.{Constants, RequestId}

class RRAdminRequest(adminApiRequest: ConfigApi.ConfigRequest,
                     esRestRequest: RestRequest) extends ActionRequest {

  val getAdminRequest: ConfigApi.ConfigRequest = adminApiRequest
  lazy val requestContextId: RequestId = RequestId(s"${esRestRequest.hashCode()}-${this.hashCode()}")


  def this() = {
    this(null, null)
  }

  override def validate(): ActionRequestValidationException = null
}

object RRAdminRequest {

  def createFrom(request: RestRequest): RRAdminRequest = {
    val requestType = (request.uri().addTrailingSlashIfNotPresent(), request.method()) match {
      case (Constants.FORCE_RELOAD_CONFIG_PATH, POST) =>
        ConfigApi.ConfigRequest.Type.ForceReload
      case (Constants.PROVIDE_FILE_CONFIG_PATH, GET) =>
        ConfigApi.ConfigRequest.Type.ProvideFileConfig
      case (Constants.PROVIDE_INDEX_CONFIG_PATH, GET) =>
        ConfigApi.ConfigRequest.Type.ProvideIndexConfig
      case (Constants.UPDATE_INDEX_CONFIG_PATH, POST) =>
        ConfigApi.ConfigRequest.Type.UpdateIndexConfig
      case (unknownUri, unknownMethod) =>
        throw new IllegalStateException(s"Unknown request: $unknownMethod $unknownUri")
    }
    new RRAdminRequest(
      new ConfigApi.ConfigRequest(
        requestType,
        request.content.utf8ToString
      ),
      request
    )
  }
}