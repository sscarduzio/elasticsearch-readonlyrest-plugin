package tech.beshu.ror.es.utils

import java.util.function.Consumer

import org.elasticsearch.common.xcontent.XContentBuilder
import org.elasticsearch.rest.{RestChannel, RestStatus}

object ErrorContentBuilderHelper {

  def createErrorResponse(channel: RestChannel,
                          status: RestStatus,
                          rootCause: Consumer[XContentBuilder]): XContentBuilder = {
    val builder = channel.newErrorBuilder.startObject
    builder.startObject("error")
    builder.startArray("root_cause")
    builder.startObject
    rootCause.accept(builder)
    builder.endObject

    builder.endArray

    rootCause.accept(builder)
    builder.field("status", status.getStatus)
    builder.endObject

    builder.endObject
  }
}
