package tech.beshu.ror.es.scala

import org.elasticsearch.common.xcontent.XContentBuilder
import org.elasticsearch.rest.{BytesRestResponse, RestChannel, RestStatus}
import tech.beshu.ror.es.utils.ErrorContentBuilderHelper.createErrorResponse

class RorNotReadyResponse private (status: RestStatus, builder: XContentBuilder)
  extends BytesRestResponse(status, builder)

object RorNotReadyResponse {
  def create(channel: RestChannel) = new RorNotReadyResponse(
    RestStatus.SERVICE_UNAVAILABLE,
    createErrorResponse(channel, RestStatus.SERVICE_UNAVAILABLE, RorNotReadyResponse.addRootCause)
  )

  private def addRootCause(builder: XContentBuilder): Unit = {
    builder.field("reason", "Waiting for ReadonlyREST start")
  }
}
