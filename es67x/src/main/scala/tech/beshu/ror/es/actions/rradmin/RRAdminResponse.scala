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

import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.ActionResponse
import org.elasticsearch.common.xcontent.{ToXContent, ToXContentObject, XContentBuilder}
import tech.beshu.ror.adminapi.AdminRestApi

class RRAdminResponse(response: AdminRestApi.AdminResponse)
  extends ActionResponse with ToXContentObject with Logging {

  override def toXContent(builder: XContentBuilder, params: ToXContent.Params): XContentBuilder = {
    response.result match {
      case AdminRestApi.Success(message) => addResponseJson(builder, "ok", message)
      case AdminRestApi.ConfigNotFound(message) => addResponseJson(builder, "empty", message)
      case AdminRestApi.Failure(message) => addResponseJson(builder, "ko", message)
    }
    builder
  }

  private def addResponseJson(builder: XContentBuilder, status: String, message: String): Unit = {
    builder.startObject
    builder.field("status", status)
    builder.field("message", message)
    builder.endObject
  }
}

object RRAdminResponse extends Logging {
  def apply(response: Either[Throwable, AdminRestApi.AdminResponse]): RRAdminResponse = {
    response match {
      case Left(ex) =>
        logger.error("RRAdmin internal error", ex)
        new RRAdminResponse(AdminRestApi.AdminResponse.internalError)
      case Right(value) =>
        new RRAdminResponse(value)
    }
  }
}
