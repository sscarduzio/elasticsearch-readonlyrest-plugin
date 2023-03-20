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

import better.files.File
import org.scalatest.{BeforeAndAfterAll, Suite}
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.utils.containers._
import tech.beshu.ror.utils.containers.providers._
import tech.beshu.ror.utils.misc.Resources.getResourcePath

trait PluginTestSupport extends PluginEsClusterProvider with CallingEsDirectly {
  this: MultipleEsTargets =>
}

trait SingletonPluginTestSupport
  extends PluginTestSupport
    with PluginEsClusterProvider
    with BeforeAndAfterAll
    with ResolvedRorConfigFileProvider {
  this: Suite with BaseSingleNodeEsClusterTest =>

  override lazy val targetEs: EsContainer = SingletonEsContainerWithRorSecurity.singleton.nodes.head

  private var startedDependencies = StartedClusterDependencies(Nil)

  override final def resolvedRorConfigFile: File = {
    resolveConfig.toTry.get
  }

  override protected def beforeAll(): Unit = {
    startedDependencies = DependencyRunner.startDependencies(clusterDependencies)
    SingletonEsContainerWithRorSecurity.cleanUpContainer()
    SingletonEsContainerWithRorSecurity.updateConfig(resolvedRorConfigFile.contentAsString)
    nodeDataInitializer.foreach(SingletonEsContainerWithRorSecurity.initNode)

    super.beforeAll()
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    startedDependencies.values.foreach(started => started.container.stop())
  }

  private def resolveConfig: Either[Throwable, File] = {
    Either.cond(
      test = startedDependencies.values.size === clusterDependencies.size,
      right = resolvedConfig(startedDependencies),
      left = new IllegalStateException("Not all dependencies are started. Cannot read resolved config yet")
    )
  }

  private def resolvedConfig(startedDependencies: StartedClusterDependencies) = {
    val configFile = File.apply(getResourcePath(rorConfigFileName))
    RorConfigAdjuster.adjustUsingDependencies(configFile, startedDependencies)
  }
}