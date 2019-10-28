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
package tech.beshu.ror.es.request

import org.elasticsearch.common.xcontent.XContentBuilder
import org.elasticsearch.rest.{BytesRestResponse, RestChannel, RestStatus}
import tech.beshu.ror.es.utils.ErrorContentBuilderHelper.createErrorResponse

class RorNotReadyResponse private (status: RestStatus, builder: XContentBuilder)
  extends BytesRestResponse(status, builder)

object RorNotReadyResponse {
  def create(channel: RestChannel): RorNotReadyResponse = new RorNotReadyResponse(
    RestStatus.SERVICE_UNAVAILABLE,
    createErrorResponse(channel, RestStatus.SERVICE_UNAVAILABLE, RorNotReadyResponse.addRootCause)
  )

  private def addRootCause(builder: XContentBuilder): Unit = {
    builder.field("reason", "Waiting for ReadonlyREST start")
  }
}
