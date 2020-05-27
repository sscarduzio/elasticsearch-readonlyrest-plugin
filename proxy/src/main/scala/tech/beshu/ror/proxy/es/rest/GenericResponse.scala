package tech.beshu.ror.proxy.es.rest

import java.nio.channels.Channels

import org.elasticsearch.action.ActionResponse
import org.elasticsearch.client.Response
import org.elasticsearch.common.io.stream.StreamOutput
import org.elasticsearch.common.xcontent.{ToXContent, ToXContentObject, XContentBuilder}
import tech.beshu.ror.es.utils.ContentBuilderHelper._

class GenericResponse(val response: Response)
  extends ActionResponse with ToXContentObject {

  override def toXContent(builder: XContentBuilder, params: ToXContent.Params): XContentBuilder = {
    builder.add(ujson.read(Channels.newChannel(response.getEntity.getContent)))
  }

  override def writeTo(out: StreamOutput): Unit = ()
}
