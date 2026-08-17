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
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.testcontainers.containers.wait.strategy.{WaitStrategy, WaitStrategyTarget}
import tech.beshu.ror.utils.containers.ToxiproxyContainer.{httpApiPort, proxiedPort, proxyName}
import tech.beshu.ror.utils.misc.ScalaUtils.*

import java.time.Duration
import scala.concurrent.duration.*
import scala.language.postfixOps
import scala.util.Try

class ToxiproxyContainer[T <: SingleContainer[_]](val innerContainer: T, innerServicePort: Int)
    extends GenericContainer(
      dockerImage = "ghcr.io/shopify/toxiproxy:2.12.0",
      exposedPorts = Seq(httpApiPort, proxiedPort),
      waitStrategy = Some(new ToxiproxyApiWaitStrategy())
    )
    with LazyLogging {

  container.setNetwork(TestNetwork.perJvm)
  container.withStartupTimeout(Duration.ofSeconds(120))

  private var innerContainerProxy: Option[Proxy] = None
  private var timeoutToxic: Option[toxic.Timeout] = None
  private var latencyToxic: Option[toxic.Latency] = None

  def containerHost: String = container.getHost

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

  def enableLatency(duration: FiniteDuration = 1 second): Unit = {
    latencyToxic = innerContainerProxy.map { proxy =>
      proxy.toxics().latency("latency", ToxicDirection.DOWNSTREAM, duration.toMillis)
    }
  }

  def disableNetworkTimeout(): Unit = {
    timeoutToxic.foreach(_.remove())
    timeoutToxic = None
  }

  def disableLatency(): Unit = {
    latencyToxic.foreach(_.remove())
    latencyToxic = None
  }

  override def start(): Unit = {
    innerContainer.start()
    super.start()

    // The admin API can drop this call ("Unexpected end of file from server") on a loaded host, and
    // the container is already up by then, so withStartupAttempts does not cover it. A retry has to
    // delete the half-created proxy first, else the next attempt fails with "proxy already exists".
    retry(times = 3, cleanBeforeRetrying = deleteProxyIfExists(), delayBetweenRetries = 2 seconds) {
      innerContainerProxy = Some(createProxy())
    }
  }

  // Best effort: this runs while the admin API is already misbehaving, so letting it throw would
  // abort the retry it is supposed to enable.
  private def deleteProxyIfExists(): Unit = {
    Try {
      Option(createToxiproxyClient().getProxyOrNull(proxyName)).foreach(_.delete())
    }.failed.foreach(ex =>
      logger.warn(s"[TOXIPROXY] Could not delete the leftover proxy before retrying: ${ex.getMessage}")
    )
  }

  override def stop(): Unit = {
    innerContainer.stop()
    super.stop()
  }

  private def createToxiproxyClient() =
    new ToxiproxyClient(container.getHost, container.getMappedPort(httpApiPort))

  private def createProxy() = {
    // Create proxy AFTER both containers are fully started
    // Fetch fresh container info to get the correct IP address
    val dockerClient = container.getDockerClient
    val freshContainerInfo = dockerClient.inspectContainerCmd(innerContainer.containerId).exec()
    val innerNetworks = freshContainerInfo.getNetworkSettings.getNetworks

    val innerContainerIp =
      if (innerNetworks.isEmpty) innerContainer.containerInfo.getConfig.getHostName
      else innerNetworks.values().iterator().next().getIpAddress

    val proxyUpstream = s"$innerContainerIp:$innerServicePort"
    val proxyListen = s"0.0.0.0:$proxiedPort"

    logger.debug(s"[TOXIPROXY] Creating proxy: listen=$proxyListen, upstream=$proxyUpstream (container IP)")
    val toxiproxyClient = createToxiproxyClient()
    val proxy = toxiproxyClient.createProxy(proxyName, proxyListen, proxyUpstream)
    logger.debug(s"[TOXIPROXY] Proxy created successfully")

    proxy
  }

}

private class ToxiproxyApiWaitStrategy extends WaitStrategy with LazyLogging {

  private var startupTimeout: Duration = Duration.ofSeconds(60)

  override def waitUntilReady(waitStrategyTarget: WaitStrategyTarget): Unit = {
    val host = waitStrategyTarget.getHost
    val port = waitStrategyTarget.getMappedPort(ToxiproxyContainer.httpApiPort)

    logger.debug(s"[TOXIPROXY_WAIT] Waiting for Toxiproxy API at $host:$port")

    retryBackoff(
      source = Task.delay(isToxiproxyReady(host, port)),
      maxRetries = 150,
      firstDelay = 1 second,
      backOffScaler = 1
    ).runSyncUnsafe(startupTimeout)
  }

  private def isToxiproxyReady(host: String, port: Int): Boolean = {
    try {
      val client = new ToxiproxyClient(host, port)
      client.getProxies
      logger.debug(s"[TOXIPROXY_WAIT] Toxiproxy API is ready at $host:$port")
      true
    } catch {
      case e: Exception =>
        logger.debug(s"[TOXIPROXY_WAIT] Toxiproxy API not ready yet: ${e.getMessage}")
        false
    }
  }

  override def withStartupTimeout(startupTimeout: Duration): WaitStrategy = {
    this.startupTimeout = startupTimeout
    this
  }

}

object ToxiproxyContainer {
  private[containers] val httpApiPort = 8474
  private[containers] val proxyName = "toxiproxy"
  val proxiedPort = 5000
}
