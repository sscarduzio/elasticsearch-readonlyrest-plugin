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
import org.testcontainers.Testcontainers
import tech.beshu.ror.utils.containers.ToxiproxyContainer.{ToxiproxyWaitStrategy, httpApiPort, proxiedPort}

import scala.concurrent.duration.*
import scala.language.postfixOps

class ToxiproxyContainer[T <: SingleContainer[_]](val innerContainer: T, innerServicePort: Int)
  extends GenericContainer(
    dockerImage = "ghcr.io/shopify/toxiproxy:2.12.0",
    exposedPorts = Seq(httpApiPort, proxiedPort),
    waitStrategy = Some(new ToxiproxyWaitStrategy(innerContainer, innerServicePort))
  ) {

  container.setNetwork(Network.SHARED)
  container.withAccessToHost(true)

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
    // Expose the inner container's mapped port BEFORE starting toxiproxy
    // This is required for host.testcontainers.internal to work on Linux
    val innerMappedPort = innerContainer.mappedPort(innerServicePort)
    println(s"[TOXIPROXY DEBUG] Exposing host port: $innerMappedPort")
    Testcontainers.exposeHostPorts(innerMappedPort)
    
    super.start()

    val toxiproxyClient = new ToxiproxyClient(container.getHost, container.getMappedPort(httpApiPort))
    innerContainerProxy = Some(toxiproxyClient.getProxy("proxy"))
    println(s"[TOXIPROXY DEBUG] Toxiproxy container mapped port (proxiedPort=$proxiedPort): ${container.getMappedPort(proxiedPort)}")
  }

  override def stop(): Unit = {
    innerContainer.stop()
    super.stop()
  }

}

object ToxiproxyContainer {

  private class ToxiproxyWaitStrategy(innerContainer: SingleContainer[_], innerServicePort: Int)
    extends WaitWithRetriesStrategy("toxiproxy") {

    override protected def isReady: Boolean = {
      try {
        val innerMappedPort = innerContainer.mappedPort(innerServicePort)
        val toxiproxyClient = new ToxiproxyClient(waitStrategyTarget.getHost, waitStrategyTarget.getMappedPort(httpApiPort))
        val proxyUpstream = s"host.testcontainers.internal:$innerMappedPort"
        val proxyListen = s"0.0.0.0:$proxiedPort"
        println(s"[TOXIPROXY DEBUG] Creating proxy: listen=$proxyListen upstream=$proxyUpstream")
        println(s"[TOXIPROXY DEBUG] Inner container service port: $innerServicePort, mapped to: $innerMappedPort")
        // Use host.testcontainers.internal to reference the host machine from within the toxiproxy container
        // This works cross-platform (Mac, Windows, Linux) in testcontainers 2.0
        // Note: exposeHostPorts must be called before container start (done in start() method)
        // Use 0.0.0.0 instead of [::] for better Linux compatibility
        val proxy = toxiproxyClient.createProxy("proxy", proxyListen, proxyUpstream)
        println(s"[TOXIPROXY DEBUG] Proxy created successfully. Proxy details: ${proxy.getName}, listen=${proxy.getListen}, upstream=${proxy.getUpstream}")
        
        // Test if we can list proxies
        val allProxies = toxiproxyClient.getProxies
        println(s"[TOXIPROXY DEBUG] All proxies count: ${allProxies.size()}")
        
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