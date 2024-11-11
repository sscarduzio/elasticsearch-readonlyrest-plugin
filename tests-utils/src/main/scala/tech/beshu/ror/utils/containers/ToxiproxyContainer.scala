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

import com.dimafeng.testcontainers.{Container, GenericContainer, SingleContainer}
import eu.rekawek.toxiproxy.ToxiproxyClient
import eu.rekawek.toxiproxy.Proxy
import eu.rekawek.toxiproxy.model.ToxicDirection
import org.testcontainers.containers.Network
import tech.beshu.ror.utils.containers.ToxiproxyContainer.{httpApiPort, proxedPort}
import eu.rekawek.toxiproxy.model.toxic

import scala.concurrent.duration.*
import scala.concurrent.duration.FiniteDuration
import scala.language.postfixOps

class ToxiproxyContainer[T <: SingleContainer[_]](val innerContainer: T, innerServicePort: Int)
  extends Container {

  private val toxiproxyContainer = {
    val container = GenericContainer(
      dockerImage = "shopify/toxiproxy:2.1.4",
      exposedPorts = Seq(httpApiPort, proxedPort),
      waitStrategy = new ToxiproxyWaitStrategy
    )
    container.container.setNetwork(Network.SHARED)
    container
  }

  private var toxiproxyClient: Option[ToxiproxyClient] = None
  private var innerContainerProxy: Option[Proxy] = None
  private var timeoutToxic: Option[toxic.Timeout] = None

  def innerContainerMappedPort: Int = toxiproxyContainer.mappedPort(proxedPort)

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
    toxiproxyContainer.start()
  }

  override def stop(): Unit = {
    innerContainer.stop()
    toxiproxyContainer.stop()
  }

  private class ToxiproxyWaitStrategy extends WaitWithRetriesStrategy("toxiproxy") {
    override protected def isReady: Boolean = {
      try {
        toxiproxyClient = Some(new ToxiproxyClient("localhost", toxiproxyContainer.mappedPort(httpApiPort)))
        innerContainerProxy = toxiproxyClient.map(_.createProxy("proxy", s"[::]:$proxedPort", s"${innerContainer.containerInfo.getConfig.getHostName}:$innerServicePort"))
        true
      } catch {
        case _: Exception => false
      }
    }
  }
}

object ToxiproxyContainer {

  private val httpApiPort = 8474
  private val proxedPort = 5000
}