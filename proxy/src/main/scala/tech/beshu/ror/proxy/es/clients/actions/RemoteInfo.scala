/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.clients.actions

import org.elasticsearch.action.admin.cluster.remote.{RemoteInfoResponse => AdminRemoteInfoResponse}
import org.elasticsearch.client.cluster.RemoteConnectionInfo.{ModeInfo => ClusterModeInfo}
import org.elasticsearch.client.cluster.{RemoteInfoResponse, ProxyModeInfo => ClusterProxyModeInfo, RemoteConnectionInfo => ClusterRemoteConnectionInfo, SniffModeInfo => ClutserSniffModeInfo}
import org.elasticsearch.common.unit.TimeValue
import org.elasticsearch.transport.ProxyConnectionStrategy.{ProxyModeInfo => TransportProxyModeInfo}
import org.elasticsearch.transport.RemoteConnectionInfo.{ModeInfo => TransportModeInfo}
import org.elasticsearch.transport.SniffConnectionStrategy.{SniffModeInfo => TransportSniffModeInfo}
import org.elasticsearch.transport.{RemoteConnectionInfo => TransportRemoteConnectionInfo}

import scala.collection.JavaConverters._

object RemoteInfo {

  implicit class RemoteInfoResponseOps(val response: RemoteInfoResponse) extends AnyVal {
    def toRemoteInfoResponse: AdminRemoteInfoResponse = {
      new AdminRemoteInfoResponse(
        response.getInfos.asScala.map(toTransportConnectionInfo).asJava
      )
    }
  }

  private def toTransportConnectionInfo(remoteConnectionInfo: ClusterRemoteConnectionInfo): TransportRemoteConnectionInfo = {
    new TransportRemoteConnectionInfo(
      remoteConnectionInfo.getClusterAlias,
      toTransportModeInfo(remoteConnectionInfo.getModeInfo),
      TimeValue.timeValueMillis(remoteConnectionInfo.getInitialConnectionTimeoutString.toInt),
      remoteConnectionInfo.isSkipUnavailable
    )
  }

  private def toTransportModeInfo(modeInfo: ClusterModeInfo): TransportModeInfo = {
    modeInfo match {
      case m: ClusterProxyModeInfo => new TransportProxyModeInfo(m.getAddress, m.getMaxSocketConnections, m.getNumSocketsConnected)
      case m: ClutserSniffModeInfo => new TransportSniffModeInfo(m.getSeedNodes, m.getMaxConnectionsPerCluster, m.getNumNodesConnected)
      case m => throw new IllegalStateException(s"Unknown mode info ${m.getClass.getName}")
    }
  }
}
