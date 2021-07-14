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
package tech.beshu.ror.es.handler.request

import org.elasticsearch.common.xcontent.XContentBuilder
import org.elasticsearch.rest.{BytesRestResponse, RestChannel, RestStatus}
import tech.beshu.ror.es.utils.ErrorContentBuilderHelper.createErrorResponse

class RorNotAvailableResponse private(status: RestStatus, builder: XContentBuilder)
  extends BytesRestResponse(status, builder)

object RorNotAvailableResponse {

  def createRorStartingFailureResponse(channel: RestChannel): RorNotAvailableResponse =
    createResponse(channel, s"ReadonlyREST failed to start")

  def createRorNotReadyYetResponse(channel: RestChannel): RorNotAvailableResponse =
    createResponse(channel, "Waiting for ReadonlyREST start")

  def createRorNotEnabledResponse(channel: RestChannel): RorNotAvailableResponse =
    createResponse(channel, "ReadonlyREST plugin was disabled in settings")

  private def createResponse(channel: RestChannel, message: String) = new RorNotAvailableResponse(
    RestStatus.SERVICE_UNAVAILABLE,
    createErrorResponse(channel, RestStatus.SERVICE_UNAVAILABLE, addRootCause(message))
  )

  private def addRootCause(message: String)(builder: XContentBuilder): Unit = {
    builder.field("reason", message)
  }
}
