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
import com.typesafe.scalalogging.LazyLogging
import eu.rekawek.toxiproxy.model.{ToxicDirection, toxic}
import eu.rekawek.toxiproxy.{Proxy, ToxiproxyClient}
import org.testcontainers.containers.Network
import tech.beshu.ror.utils.containers.ToxiproxyContainer.{ToxiproxyWaitStrategy, httpApiPort, proxiedPort}

import scala.annotation.nowarn
import scala.concurrent.duration.*
import scala.language.postfixOps

class ToxiproxyContainer[T <: SingleContainer[_]](val innerContainer: T, innerServicePort: Int)
  extends GenericContainer(
    dockerImage = "shopify/toxiproxy:2.1.4",
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
    extends WaitWithRetriesStrategy("toxiproxy")
      with LazyLogging {

    @nowarn("cat=deprecation")
    override protected def isReady: Boolean = {
      try {
        val innerMappedPort = innerContainer.mappedPort(innerServicePort)
        val toxiproxyClient = new ToxiproxyClient(waitStrategyTarget.getHost, waitStrategyTarget.getMappedPort(httpApiPort))
        
        // Get the gateway IP from the toxiproxy container's network settings
        // This is the IP address that toxiproxy can use to reach the host machine
        val networks = waitStrategyTarget.getContainerInfo.getNetworkSettings.getNetworks
        val gatewayIp = if (networks.isEmpty) {
          waitStrategyTarget.getContainerInfo.getNetworkSettings.getGateway
        } else {
          networks.values().iterator().next().getGateway
        }
        
        val proxyUpstream = s"$gatewayIp:$innerMappedPort"
        val proxyListen = s"0.0.0.0:$proxiedPort"
        
        logger.debug(s"[TOXIPROXY] Creating proxy: listen=$proxyListen, upstream=$proxyUpstream (gateway IP)")
        toxiproxyClient.createProxy("proxy", proxyListen, proxyUpstream)
        logger.debug(s"[TOXIPROXY] Proxy created successfully")
        
        true
      } catch {
        case ex: Throwable =>
          logger.debug("Toxic Proxy is not ready", ex)
          false
      }
    }
  }

  private val httpApiPort = 8474
  val proxiedPort = 5000
}