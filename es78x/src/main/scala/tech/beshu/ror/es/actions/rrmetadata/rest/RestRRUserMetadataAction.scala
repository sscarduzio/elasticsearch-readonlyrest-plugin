package tech.beshu.ror.es.actions.rrmetadata.rest

import java.util

import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.rest.BaseRestHandler.RestChannelConsumer
import org.elasticsearch.rest.RestHandler.Route
import org.elasticsearch.rest.RestRequest.Method.GET
import org.elasticsearch.rest.action.RestToXContentListener
import org.elasticsearch.rest.{BaseRestHandler, RestChannel, RestHandler, RestRequest}
import tech.beshu.ror.Constants
import tech.beshu.ror.es.actions.rrmetadata.{RRUserMetadataActionType, RRUserMetadataRequest, RRUserMetadataResponse}

import scala.collection.JavaConverters._

@Inject
class RestRRUserMetadataAction()
  extends BaseRestHandler with RestHandler {

  override def routes(): util.List[Route] = List(
    new Route(GET, Constants.CURRENT_USER_METADATA_PATH)
  ).asJava

  override val getName: String = "ror-user-metadata-handler"

  override def prepareRequest(request: RestRequest, client: NodeClient): RestChannelConsumer = (channel: RestChannel) => {
    client.execute(new RRUserMetadataActionType, new RRUserMetadataRequest, new RestToXContentListener[RRUserMetadataResponse](channel))
  }
}
