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
import org.elasticsearch.common.io.stream.{StreamInput, StreamOutput, Writeable}
import org.elasticsearch.rest.RestRequest
import tech.beshu.ror.adminapi.AdminRestApi

final case class RRAdminRequest(private var request: AdminRestApi.AdminRequest) extends ActionRequest {

  def this(request: RestRequest) = {
    this(AdminRestApi.AdminRequest(request.method.name, request.path, request.content.utf8ToString))
  }

  override def writeTo(out: StreamOutput): Unit = {
    super.writeTo(out)
    RRAdminRequest.writer.write(out, this)
  }

  def this() = {
    this(null: AdminRestApi.AdminRequest)
  }

  val getAdminRequest: AdminRestApi.AdminRequest = request

  override def validate() = null
}
object RRAdminRequest {
  val reader: Writeable.Reader[RRAdminRequest] = in => {
    val request = AdminRestApi.AdminRequest(
      method = in.readString(),
      uri = in.readString(),
      body = in.readString()
    )
    new RRAdminRequest(request)
  }
  val writer:Writeable.Writer[RRAdminRequest] = (out, request) => {
    out.writeString(request.request.method)
    out.writeString(request.request.uri)
    out.writeString(request.request.body)
  }
}
