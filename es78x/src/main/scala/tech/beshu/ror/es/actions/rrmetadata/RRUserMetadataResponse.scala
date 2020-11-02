package tech.beshu.ror.es.actions.rrmetadata

import org.elasticsearch.action.ActionResponse
import org.elasticsearch.common.io.stream.StreamOutput
import org.elasticsearch.common.xcontent.{ToXContent, ToXContentObject, XContentBuilder}

class RRUserMetadataResponse extends ActionResponse with ToXContentObject {

  override def writeTo(out: StreamOutput): Unit = ()

  override def toXContent(builder: XContentBuilder, params: ToXContent.Params): XContentBuilder = builder
}
