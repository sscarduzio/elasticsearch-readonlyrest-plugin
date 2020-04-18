package tech.beshu.ror.es.rrconfig.rest

import java.util.function.Supplier

import org.elasticsearch.action.FailedNodeException
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.cluster.node.DiscoveryNodes
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.common.unit.TimeValue
import org.elasticsearch.common.xcontent.XContentBuilder
import org.elasticsearch.rest.BaseRestHandler.RestChannelConsumer
import org.elasticsearch.rest._
import org.elasticsearch.rest.action.RestBuilderListener
import tech.beshu.ror.adminapi.AdminRestApi
import tech.beshu.ror.es.rrconfig.NodesResponse.{ClusterName, NodeError, NodeId, NodeResponse}
import tech.beshu.ror.es.rrconfig.{NodeConfigRequest, NodesResponse, RRConfig, RRConfigAction, RRConfigsRequest, RRConfigsResponse, Timeout}

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.language.postfixOps

@Inject
class RestRRConfigAction(controller: RestController,
                         nodesInCluster: Supplier[DiscoveryNodes])
  extends BaseRestHandler {

  register("GET", AdminRestApi.provideRorConfigPath.endpointString)

  override val getName: String = "ror-config-handler"

  override def prepareRequest(request: RestRequest, client: NodeClient): RestChannelConsumer = {
    val timeout = getTimeout(request, RestRRConfigAction.defaultTimeout)
    val requestConfig = NodeConfigRequest(
      timeout = Timeout(timeout.nanos())
    )
    channel => {
      client.execute(new RRConfigAction, new RRConfigsRequest(requestConfig, nodes.toArray: _*), new ResponseBuilder(channel))
    }
  }

  private def getTimeout(request: RestRequest, default: TimeValue) =
    request.paramAsTime("timeout", default)

  private def nodes =
    nodesInCluster.get().asScala.toList

  private def register(method: String, path: String): Unit =
    controller.registerHandler(RestRequest.Method.valueOf(method), path, this)
}
object RestRRConfigAction {
  val defaultTimeout: TimeValue = TimeValue.timeValueMinutes(1) //TODO move out from here
}
final class ResponseBuilder(channel: RestChannel) extends RestBuilderListener[RRConfigsResponse](channel) {
  override def buildResponse(response: RRConfigsResponse, builder: XContentBuilder): RestResponse = {
    val nodeResponse = createNodesResponse(response)
    new BytesRestResponse(RestStatus.OK, nodeResponse.toJson)
  }

  private def createNodesResponse(response: RRConfigsResponse) = {
    NodesResponse(ClusterName(response.getClusterName.value()), response.getNodes.asScala.toList.map(createNodeResponse), response.failures().asScala.toList.map(createNodeError))
  }

  private def createNodeResponse(config: RRConfig) = {
    NodeResponse(NodeId(config.getNode.getId), config.getNodeConfig.loadedConfig)
  }

  private def createNodeError(failedNodeException: FailedNodeException) = {
    NodeError(NodeId(failedNodeException.nodeId()), failedNodeException.getDetailedMessage)
  }

}