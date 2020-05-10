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
import com.dimafeng.testcontainers.ForAllTestContainer
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.apache.logging.log4j.scala.Logging
import org.scalatest.{BeforeAndAfterAll, Suite}
import tech.beshu.ror.integration.suites.base.support.BasicSingleNodeEsClusterSupport
import tech.beshu.ror.proxy.{RorProxy, RorProxyApp}
import tech.beshu.ror.utils.containers._
import tech.beshu.ror.utils.containers.providers.{CallingProxy, MultipleEsTargets, RorConfigFileNameProvider}

import scala.concurrent.duration._
import scala.language.postfixOps

trait ProxyTestSupport
  extends BeforeAndAfterAll
    with ForAllTestContainer
    with CallingProxy
    with EsWithoutRorPluginContainerCreator
    with Logging {
  this: Suite with MultipleEsTargets with RorConfigFileNameProvider =>

  private var launchedProxies: NonEmptyList[RorProxyApp] = _

  override lazy val proxyPorts: NonEmptyList[Int] = launchedProxies.map(app => app.config.proxyPort)

  override def afterStart(): Unit = {
    super.afterStart()
    val dependencies = container match {
      case c: EsClusterContainer => c.startedDependencies
      case _ => StartedClusterDependencies(Nil)
    }
    launchedProxies = esTargets
      .zipWithIndex
      .map {
        case (esContainer, index) => launchProxy(esContainer, index, dependencies)
      }
    Task(launchedProxies.forall(_.isAppStarted))
      .restartUntil(started => started)
      .timeoutWith(1 minute, new Exception("Cannot start Proxy instances"))
      .runSyncUnsafe()
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    // todo: clean
    //    launchedProxies
    //      .traverse(app => app._2())
    //      .unsafeRunSync()
  }

  private def launchProxy(esContainer: EsContainer, appIndex: Int, startedDependencies: StartedClusterDependencies) = {
    val proxyPort = appIndex + 5000
    this.logger.info(s"Starting proxy instance listening on port: $proxyPort, target ES node: ${esContainer.name}")
    val testProxyApp = createApp(proxyPort, esContainer, startedDependencies)
    testProxyApp
      .run(Nil)
      .unsafeRunAsyncAndForget()
    testProxyApp
  }

  //TODO: Create proxy instance dynamically based on 'esModule' property. Now it's fixed on es74-cloud
  private def createApp(port: Int,
                        targetEsContainer: EsContainer,
                        startedDependencies: StartedClusterDependencies): RorProxyApp = new RorProxyApp {
    private val rawRorConfig = ContainerUtils.getResourceFile(rorConfigFileName)
    private val adjustedRorConfig = RorConfigAdjuster.adjustUsingDependencies(
      rawRorConfig.toScala,
      startedDependencies,
      RorConfigAdjuster.Mode.Proxy
    )

    System.setProperty("com.readonlyrest.settings.file.path", adjustedRorConfig.toJava.getAbsolutePath)

    override def config: RorProxy.Config = RorProxy.Config(
      targetEsNode = s"http://${targetEsContainer.host}:${targetEsContainer.port}",
      proxyPort = port,
      esConfigFile = ContainerUtils.getResourceFile("/proxy/elasticsearch.yml").toScala
    )
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