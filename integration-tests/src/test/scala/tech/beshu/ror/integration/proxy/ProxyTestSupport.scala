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
package tech.beshu.ror.integration.proxy

import better.files._
import cats.data.NonEmptyList
import cats.effect.{ContextShift, IO}
import com.dimafeng.testcontainers.ForAllTestContainer
import org.apache.logging.log4j.scala.Logging
import org.scalatest.{BeforeAndAfterAll, Suite}
import tech.beshu.ror.integration.suites.base.support.BasicSingleNodeEsClusterSupport
import tech.beshu.ror.proxy.RorProxy
import tech.beshu.ror.proxy.RorProxy.ProxyAppWithCloseHandler
import tech.beshu.ror.utils.containers.providers.{CallingProxy, MultipleEsTargets, RorConfigFileNameProvider}
import tech.beshu.ror.utils.containers._

import scala.concurrent.ExecutionContext

trait ProxyTestSupport
  extends BeforeAndAfterAll
    with ForAllTestContainer
    with CallingProxy
    with EsWithoutRorPluginContainerCreator
    with Logging {
  this: Suite with MultipleEsTargets with RorConfigFileNameProvider =>

  private var launchedProxies: NonEmptyList[ProxyAppWithCloseHandler] = _

  override lazy val proxyPorts: NonEmptyList[Int] = launchedProxies.map(app => app._1.config.proxyPort)

  override def afterStart(): Unit = {
    super.afterStart()
    launchedProxies = esTargets.zipWithIndex
      .map {
        case (esContainer, index) => launchProxy(esContainer, index)
      }
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    launchedProxies
      .traverse(app => app._2())
      .unsafeRunSync()
  }

  private def launchProxy(esContainer: EsContainer, appIndex: Int) = {
    val proxyPort = appIndex + 5000
    this.logger.info(s"Starting proxy instance listening on port: $proxyPort, target ES node: ${esContainer.name}")
    val testProxyApp = createApp(proxyPort, esContainer)
    val closeHandler = testProxyApp
      .start
      .unsafeRunSync()
      .getOrElse(throw new IllegalStateException("Could not start test proxy instance"))
    (testProxyApp, closeHandler)
  }

  //TODO: Create proxy instance dynamically based on 'esModule' property. Now it's fixed on es74-cloud
  private def createApp(port: Int, targetEsContainer: EsContainer): RorProxy = new RorProxy {

    private val rawRorConfig = ContainerUtils.getResourceFile(rorConfigFileName)
    private val adjustedRorConfig = RorConfigAdjuster.adjustUsingDependencies(
      rawRorConfig.toScala,
      StartedClusterDependencies(Nil), // todo: fixme
      RorConfigAdjuster.Mode.Proxy
    )

    System.setProperty("com.readonlyrest.settings.file.path", adjustedRorConfig.toJava.getAbsolutePath)

    override def config: RorProxy.Config = RorProxy.Config(
      targetEsNode = s"http://${targetEsContainer.host}:${targetEsContainer.port}",
      proxyPort = port,
      esConfigFile = ContainerUtils.getResourceFile("/proxy/elasticsearch.yml").toScala
    )

    override implicit protected def contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.Implicits.global)
  }
}

trait BasicClusterProxyTestSupport extends ProxyTestSupport {
  this: Suite with BasicSingleNodeEsClusterSupport =>

  override lazy val container = createLocalClusterContainer(
    nodeDataInitializer.foldLeft(EsClusterSettings.basic) {
      case (settings, definedInitializer) => settings.copy(nodeDataInitializer = definedInitializer)
    }
  )

  override lazy val targetEs = container.nodes.head
}