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
package tech.beshu.ror.es.rradmin

import org.elasticsearch.action.ActionRequest
import org.elasticsearch.rest.RestRequest
import tech.beshu.ror.Constants
import tech.beshu.ror.adminapi.AdminRestApi
import org.elasticsearch.rest.RestRequest.Method.{GET, POST}

class RRAdminRequest(request: RestRequest) extends ActionRequest {

  def this() {
    this(null)
  }

  lazy val getAdminRequest: AdminRestApi.AdminRequest = {
    val requestType = (request.uri(), request.method()) match {
      case (uri, method) if uri == Constants.FORCE_RELOAD_CONFIG_PATH && method == POST =>
        AdminRestApi.AdminRequest.Type.ForceReload
      case (uri, method) if uri == Constants.PROVIDE_INDEX_CONFIG_PATH && method == GET =>
        AdminRestApi.AdminRequest.Type.ProvideIndexConfig
      case (uri, method) if uri == Constants.UPDATE_INDEX_CONFIG_PATH && method == POST =>
        AdminRestApi.AdminRequest.Type.UpdateIndexConfig
      case (uri, method) if uri == Constants.PROVIDE_FILE_CONFIG_PATH && method == GET =>
        AdminRestApi.AdminRequest.Type.ProvideFileConfig
      case (unknownUri, unknownMethod) =>
        throw new IllegalStateException(s"Unknown request: $unknownMethod $unknownUri")
    }
    new AdminRestApi.AdminRequest(requestType, request.method.name, request.path, request.content.utf8ToString)
  }

  override def validate() = null
}
