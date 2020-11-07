package tech.beshu.ror.es.actions.rrauditevent.rest

import java.util

import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.common.xcontent.{XContentBuilder, XContentHelper}
import org.elasticsearch.rest.BaseRestHandler.RestChannelConsumer
import org.elasticsearch.rest.RestHandler.Route
import org.elasticsearch.rest.RestRequest.Method.POST
import org.elasticsearch.rest._
import org.json.JSONObject
import tech.beshu.ror.Constants
import tech.beshu.ror.es.actions.rrauditevent.{RRAuditEventActionType, RRAuditEventRequest}
import tech.beshu.ror.es.utils.ErrorContentBuilderHelper.createErrorResponse

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

@Inject
class RestRRAuditEventAction()
  extends BaseRestHandler with RestHandler {

  override def routes(): util.List[Route] = List(
    new Route(POST, Constants.AUDIT_EVENT_COLLECTOR_PATH)
  ).asJava

  override val getName: String = "ror-audit-event-collector-handler"

  override def prepareRequest(request: RestRequest, client: NodeClient): RestChannelConsumer = (channel: RestChannel) => {
    bodyJsonFrom(request) match {
      case Success(json) =>
        client.execute(
          new RRAuditEventActionType,
          new RRAuditEventRequest(json),
          new RestRRAuditEventActionResponseBuilder(channel)
        )
      case Failure(_) =>
        channel.sendResponse(new RestRRAuditEventBadRequest(channel))
    }
  }

  private def bodyJsonFrom(request: RestRequest) = Try {
    if (request.hasContent) {
      new JSONObject(XContentHelper.convertToMap(request.requiredContent(), false, request.getXContentType).v2())
    } else {
      new JSONObject()
    }
  }

  private class RestRRAuditEventBadRequest(channel: RestChannel) extends BytesRestResponse(
    RestStatus.BAD_REQUEST,
    createErrorResponse(
      channel,
      RestStatus.BAD_REQUEST,
      (builder: XContentBuilder) => builder.field("reason", "Content malformed")
    )
  )
}
