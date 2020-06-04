/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.genericaction

import java.nio.channels.Channels

import org.elasticsearch.action.ActionResponse
import org.elasticsearch.client.Response
import org.elasticsearch.common.io.stream.StreamOutput
import org.elasticsearch.common.xcontent.json.JsonXContent
import org.elasticsearch.common.xcontent.{ToXContent, ToXContentObject, XContentBuilder}
import org.elasticsearch.rest.{BytesRestResponse, RestResponse, RestStatus}
import tech.beshu.ror.es.utils.ContentBuilderHelper._

class GenericResponse(val response: Response)
  extends ActionResponse with ToXContentObject {

  override def toXContent(builder: XContentBuilder, params: ToXContent.Params): XContentBuilder = {
    builder.add(ujson.read(Channels.newChannel(response.getEntity.getContent)))
  }

  override def writeTo(out: StreamOutput): Unit = ()

  def toRestResponse: RestResponse = new BytesRestResponse(
    RestStatus.fromCode(response.getStatusLine.getStatusCode),
    toXContent(JsonXContent.contentBuilder(), ToXContent.EMPTY_PARAMS)
  )
}
