/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.es.actions.rrconfig.rest

import java.util.function.Supplier

import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.cluster.node.DiscoveryNodes
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.common.unit.TimeValue
import org.elasticsearch.rest.BaseRestHandler.RestChannelConsumer
import org.elasticsearch.rest._
import tech.beshu.ror.constants
import tech.beshu.ror.configuration.loader.distributed.NodesResponse.NodeId
import tech.beshu.ror.configuration.loader.distributed.{NodeConfigRequest, Timeout}
import tech.beshu.ror.es.actions.rrconfig.{RRConfigActionType, RRConfigsRequest}

import scala.jdk.CollectionConverters._

@Inject
class RestRRConfigAction(controller: RestController,
                         nodesInCluster: Supplier[DiscoveryNodes])
  extends BaseRestHandler {

  register("GET", constants.MANAGE_ROR_CONFIG_PATH)

  override val getName: String = "ror-config-handler"

  override def prepareRequest(request: RestRequest, client: NodeClient): RestChannelConsumer = {
    val timeout = getTimeout(request, RestRRConfigAction.defaultTimeout)
    val requestConfig = NodeConfigRequest(
      timeout = Timeout(timeout.nanos())
    )
    channel =>
      client.execute(
        new RRConfigActionType,
        new RRConfigsRequest(requestConfig, nodes.toArray: _*),
        new RestRRConfigActionResponseBuilder(NodeId(client.getLocalNodeId), channel)
      )
  }

  private def getTimeout(request: RestRequest, default: TimeValue) =
    request.paramAsTime("timeout", default)

  private def nodes =
    nodesInCluster.get().asScala.toList

  private def register(method: String, path: String): Unit =
    controller.registerHandler(RestRequest.Method.valueOf(method), path, this)
}
object RestRRConfigAction {
  private val defaultTimeout: TimeValue = toTimeValue(NodeConfigRequest.defaultTimeout)
  private def toTimeValue(timeout: Timeout):TimeValue = TimeValue.timeValueNanos(timeout.nanos)
}
