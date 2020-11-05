package tech.beshu.ror.es.actions.rrauditevent.rest

import org.elasticsearch.common.xcontent.XContentBuilder
import org.elasticsearch.rest.action.RestBuilderListener
import org.elasticsearch.rest.{BytesRestResponse, RestChannel, RestResponse, RestStatus}
import tech.beshu.ror.es.actions.rrauditevent.RRAuditEventResponse

class RestRRAuditEventActionResponseBuilder(channel: RestChannel)
  extends RestBuilderListener[RRAuditEventResponse](channel) {

  override def buildResponse(response: RRAuditEventResponse, builder: XContentBuilder): RestResponse = {
    new BytesRestResponse(RestStatus.NO_CONTENT, "")
  }
}
