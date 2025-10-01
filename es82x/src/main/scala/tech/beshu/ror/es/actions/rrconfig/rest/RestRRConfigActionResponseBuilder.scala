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

import java.util.concurrent.TimeoutException

import org.elasticsearch.action.FailedNodeException
import org.elasticsearch.xcontent.XContentBuilder
import org.elasticsearch.rest.action.RestBuilderListener
import org.elasticsearch.rest.{BytesRestResponse, RestChannel, RestResponse, RestStatus}
import org.elasticsearch.transport.ActionNotFoundTransportException
import tech.beshu.ror.settings.es.loader.distributed.NodesResponse
import tech.beshu.ror.settings.es.loader.distributed.NodesResponse.{NodeError, NodeId, NodeResponse}
import tech.beshu.ror.es.actions.rrconfig.{RRConfig, RRConfigsResponse}

import scala.jdk.CollectionConverters.*

final class RestRRConfigActionResponseBuilder(localNode: NodeId, channel: RestChannel)
  extends RestBuilderListener[RRConfigsResponse](channel) {

  override def buildResponse(response: RRConfigsResponse, builder: XContentBuilder): RestResponse = {
    val nodeResponse = createNodesResponse(response)
    new BytesRestResponse(RestStatus.OK, nodeResponse.toJson)
  }

  private def createNodesResponse(response: RRConfigsResponse) =
    NodesResponse.create(
      localNode = localNode,
      responses = response.getNodes.asScala.toList.map(createNodeResponse),
      failures = response.failures().asScala.toList.map(createNodeError),
    )

  private def createNodeResponse(config: RRConfig) = {
    NodeResponse(NodeId(config.getNode.getId), config.getNodeConfig.loadedConfig)
  }

  private def createNodeError(failedNodeException: FailedNodeException) = {
    NodeError(NodeId(failedNodeException.nodeId()), createCause(failedNodeException))
  }

  private def createCause(failedNodeException: FailedNodeException): NodeError.Cause = {
    failedNodeException.getRootCause match {
      case _: TimeoutException =>
        NodeError.Timeout
      case _: ActionNotFoundTransportException =>
        NodeError.RorConfigActionNotFound
      case _ =>
        NodeError.Unknown(failedNodeException.getDetailedMessage)
    }
  }
}
