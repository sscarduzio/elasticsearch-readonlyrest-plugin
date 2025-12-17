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
package tech.beshu.ror.utils.containers

import com.dimafeng.testcontainers.{GenericContainer, SingleContainer}
import eu.rekawek.toxiproxy.model.{ToxicDirection, toxic}
import eu.rekawek.toxiproxy.{Proxy, ToxiproxyClient}
import org.testcontainers.containers.Network
import tech.beshu.ror.utils.containers.ToxiproxyContainer.{ToxiproxyWaitStrategy, httpApiPort, proxiedPort}

import scala.annotation.nowarn
import scala.concurrent.duration.*
import scala.language.postfixOps

class ToxiproxyContainer[T <: SingleContainer[_]](val innerContainer: T, innerServicePort: Int)
  extends GenericContainer(
    dockerImage = "ghcr.io/shopify/toxiproxy:2.12.0",
    exposedPorts = Seq(httpApiPort, proxiedPort),
    waitStrategy = Some(new ToxiproxyWaitStrategy(innerContainer, innerServicePort))
  ) {

  container.setNetwork(Network.SHARED)

  private var innerContainerProxy: Option[Proxy] = None
  private var timeoutToxic: Option[toxic.Timeout] = None

  def innerContainerMappedPort: Int = container.getMappedPort(proxiedPort)

  def disableNetwork(): Unit = {
    innerContainerProxy.foreach(_.disable())
  }

  def enableNetwork(): Unit = {
    innerContainerProxy.foreach(_.enable())
  }

  def enableNetworkTimeout(duration: FiniteDuration = 1 second): Unit = {
    timeoutToxic = innerContainerProxy.map { proxy =>
      proxy.toxics().timeout("timeout", ToxicDirection.DOWNSTREAM, duration.toMillis)
    }
  }

  def disableNetworkTimeout(): Unit = {
    timeoutToxic.foreach(_.remove())
    timeoutToxic = None
  }

  override def start(): Unit = {
    innerContainer.start()
    super.start()

    val toxiproxyClient = new ToxiproxyClient(container.getHost, container.getMappedPort(httpApiPort))
    innerContainerProxy = Some(toxiproxyClient.getProxy("proxy"))
  }

  override def stop(): Unit = {
    innerContainer.stop()
    super.stop()
  }

}

object ToxiproxyContainer {

  private class ToxiproxyWaitStrategy(innerContainer: SingleContainer[_], innerServicePort: Int)
    extends WaitWithRetriesStrategy("toxiproxy") {

    @nowarn("cat=deprecation")
    override protected def isReady: Boolean = {
      try {
        val innerMappedPort = innerContainer.mappedPort(innerServicePort)
        val toxiproxyClient = new ToxiproxyClient(waitStrategyTarget.getHost, waitStrategyTarget.getMappedPort(httpApiPort))
        
        // Get the gateway IP from the toxiproxy container's network settings
        // This is the IP address that toxiproxy can use to reach the host machine
        val networks = waitStrategyTarget.getContainerInfo.getNetworkSettings.getNetworks
        val gatewayIp = if (networks.isEmpty) {
          // Fallback to deprecated method if networks are empty (shouldn't happen)
          waitStrategyTarget.getContainerInfo.getNetworkSettings.getGateway
        } else {
          // Get gateway from the first network (usually bridge or the shared network)
          networks.values().iterator().next().getGateway
        }
        
        val proxyUpstream = s"$gatewayIp:$innerMappedPort"
        val proxyListen = s"0.0.0.0:$proxiedPort"
        
        println(s"[TOXIPROXY DEBUG] Creating proxy:")
        println(s"[TOXIPROXY DEBUG]   listen=$proxyListen")
        println(s"[TOXIPROXY DEBUG]   upstream=$proxyUpstream (gateway IP)")
        println(s"[TOXIPROXY DEBUG]   Inner container service port: $innerServicePort, mapped to: $innerMappedPort")
        
        // Create proxy pointing to the host's gateway IP + mapped port
        // From inside toxiproxy container, we reach the host via the Docker bridge gateway
        toxiproxyClient.createProxy("proxy", proxyListen, proxyUpstream)
        println(s"[TOXIPROXY DEBUG] Proxy created successfully")
        
        true
      } catch {
        case ex: Exception => 
          println(s"[TOXIPROXY DEBUG] Failed to create proxy: ${ex.getClass.getName}: ${ex.getMessage}")
          ex.printStackTrace()
          false
      }
    }
  }

  private val httpApiPort = 8474
  val proxiedPort = 5000
}