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

import org.elasticsearch.action.ActionRequest
import org.elasticsearch.rest.RestRequest
import tech.beshu.ror.adminapi.AdminRestApi

class RRAdminRequest(request: AdminRestApi.AdminRequest) extends ActionRequest {

  def this(request: RestRequest) = {
    this(AdminRestApi.AdminRequest(request.method.name, request.path, request.content.utf8ToString))
  }

  def this() = {
    this(null: AdminRestApi.AdminRequest)
  }

  val getAdminRequest: AdminRestApi.AdminRequest = request

  override def validate() = null
}
