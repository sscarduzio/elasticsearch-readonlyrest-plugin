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
package tech.beshu.ror.integration.utils

import better.files._
import com.dimafeng.testcontainers.ForAllTestContainer
import com.typesafe.scalalogging.LazyLogging
import monix.execution.Scheduler.Implicits.global
import org.scalatest.{BeforeAndAfterAll, Suite}
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.utils.containers._
import tech.beshu.ror.utils.containers.providers.{CallingProxy, MultipleEsTargets, RorConfigFileNameProvider}
import tech.beshu.ror.utils.proxy.RorProxyInstance

import scala.language.postfixOps

trait ProxyTestSupport
  extends BeforeAndAfterAll
    with ForAllTestContainer
    with CallingProxy
    with EsWithoutSecurityPluginContainerCreator
    with LazyLogging {
  this: Suite with MultipleEsTargets with RorConfigFileNameProvider =>

  private var launchedProxy: RorProxyInstance = _

  override def proxy: RorProxyInstance = launchedProxy

  override def afterStart(): Unit = {
    super.afterStart()
    launchedProxy = launchProxy(esTargets.head, 5000)
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    launchedProxy.close()
  }

  private def launchProxy(esContainer: EsContainer, proxyPort: Int) = {
    val rawRorConfig = ContainerUtils.getResourceFile(rorConfigFileName)
    val adjustedRorConfig = RorConfigAdjuster.adjustUsingDependencies(
      rawRorConfig.toScala,
      esTargets.head.startedClusterDependencies,
      RorConfigAdjuster.Mode.Proxy
    )
    val instance = RorProxyInstance
      .start(
        proxyPort,
        adjustedRorConfig,
        esTargets.head.containerIpAddress,
        esTargets.head.port,
        esContainer.esClusterSettings.rorContainerSpecification.environmentVariables
      )
      .runSyncUnsafe()
    instance
  }
}

sealed trait BasicEsClusterProxyTestSupport extends ProxyTestSupport {
  this: Suite with BaseSingleNodeEsClusterTest =>

  protected def xpackSupport: Boolean

  private def clusterSettings = {
    val enhancedSettings = EsClusterSettings.basic
      .copy(xPackSupport = xpackSupport)
      .copy(dependentServicesContainers = clusterDependencies)

    nodeDataInitializer match {
      case Some(definedInitializer) => enhancedSettings.copy(nodeDataInitializer = definedInitializer)
      case None => enhancedSettings
    }
  }

  override lazy val container = createLocalClusterContainer(clusterSettings)
  override lazy val targetEs = container.nodes.head
}

trait XpackEsClusterProxyTestSupport extends BasicEsClusterProxyTestSupport {
  this: Suite with BaseSingleNodeEsClusterTest =>

  override def xpackSupport: Boolean = true
}

trait OssEsClusterProxyTestSupport extends BasicEsClusterProxyTestSupport {
  this: Suite with BaseSingleNodeEsClusterTest =>

  override def xpackSupport: Boolean = false
}
