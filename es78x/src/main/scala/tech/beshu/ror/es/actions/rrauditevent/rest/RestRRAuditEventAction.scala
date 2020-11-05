package tech.beshu.ror.es.actions.rrauditevent.rest

import java.util

import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.common.xcontent.XContentHelper
import org.elasticsearch.rest.BaseRestHandler.RestChannelConsumer
import org.elasticsearch.rest.RestHandler.Route
import org.elasticsearch.rest.RestRequest.Method.POST
import org.elasticsearch.rest.{BaseRestHandler, RestChannel, RestHandler, RestRequest}
import org.json.JSONObject
import tech.beshu.ror.Constants
import tech.beshu.ror.es.actions.rrauditevent.{RRAuditEventActionType, RRAuditEventRequest}

import scala.collection.JavaConverters._

@Inject
class RestRRAuditEventAction()
  extends BaseRestHandler with RestHandler {

  override def routes(): util.List[Route] = List(
    new Route(POST, Constants.AUDIT_EVENT_COLLECTOR_PATH)
  ).asJava

  override val getName: String = "ror-audit-event-collector-handler"

  override def prepareRequest(request: RestRequest, client: NodeClient): RestChannelConsumer = (channel: RestChannel) => {
    client.execute(
      new RRAuditEventActionType,
      new RRAuditEventRequest(bodyJsonFrom(request)),
      new RestRRAuditEventActionResponseBuilder(channel)
    )
  }

  private def bodyJsonFrom(request: RestRequest) = {
    if (request.hasContent) {
      XContentHelper
        .convertToMap(request.requiredContent(), false, request.getXContentType).v2()
        .asScala
        .mapValues(new JSONObject(_))
        .toMap
    } else {
      Map.empty[String, JSONObject]
    }
  }
}
