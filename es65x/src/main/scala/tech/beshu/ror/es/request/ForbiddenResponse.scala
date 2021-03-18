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
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.es.request.handler.RegularRequestHandler.ForbiddenCause
import tech.beshu.ror.es.utils.ErrorContentBuilderHelper.createErrorResponse

class ForbiddenResponse private(status: RestStatus, builder: XContentBuilder)
  extends BytesRestResponse(status, builder)

object ForbiddenResponse {

  def create(channel: RestChannel,
             causes: List[ForbiddenCause],
             aclStaticContext: AccessControlStaticContext): ForbiddenResponse = {
    val restStatus = responseRestStatus(aclStaticContext)
    val response = new ForbiddenResponse(
      restStatus,
      createErrorResponse(channel, restStatus, (builder: XContentBuilder) => addRootCause(builder, causes, aclStaticContext))
    )
    if (aclStaticContext.doesRequirePassword) {
      response.addHeader("WWW-Authenticate", "Basic")
    }
    response
  }

  private def addRootCause(builder: XContentBuilder,
                           causes: List[ForbiddenCause],
                           aclStaticContext: AccessControlStaticContext): Unit = {
    builder.field("reason", aclStaticContext.forbiddenRequestMessage)
    builder.field("due_to", causes.map(_.stringify).toArray)
  }

  private def responseRestStatus(aclStaticContext: AccessControlStaticContext): RestStatus =
    if (aclStaticContext.doesRequirePassword) RestStatus.UNAUTHORIZED
    else RestStatus.FORBIDDEN
}
