package tech.beshu.ror.es

import org.elasticsearch.common.xcontent.XContentBuilder
import org.elasticsearch.rest.{BytesRestResponse, RestChannel, RestStatus}
import tech.beshu.ror.acl.{AclActionHandler, AclStaticContext}
import tech.beshu.ror.es.utils.ErrorContentBuilderHelper.createErrorResponse

class ForbiddenResponse private(status: RestStatus, builder: XContentBuilder)
  extends BytesRestResponse(status, builder)

object ForbiddenResponse {

  def create(channel: RestChannel,
             causes: List[AclActionHandler.ForbiddenCause],
             aclStaticContext: AclStaticContext): ForbiddenResponse = {
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
                           causes: List[AclActionHandler.ForbiddenCause],
                           aclStaticContext: AclStaticContext): Unit = {
    builder.field("reason", aclStaticContext.forbiddenRequestMessage)
    builder.field("due_to", causes.map(_.stringify).toArray)
  }

  private def responseRestStatus(aclStaticContext: AclStaticContext): RestStatus =
    if (aclStaticContext.doesRequirePassword) RestStatus.UNAUTHORIZED
    else RestStatus.FORBIDDEN
}
