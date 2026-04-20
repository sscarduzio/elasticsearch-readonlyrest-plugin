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
import tech.beshu.ror.utils.containers.*
import tech.beshu.ror.utils.containers.providers.*
import tech.beshu.ror.utils.misc.Resources.getResourcePath

trait PluginTestSupport extends EsClusterProvider with CallingEsDirectly {
  this: MultipleEsTargets =>
}

trait SingletonPluginTestSupport
  extends PluginTestSupport
    with EsClusterProvider
    with BeforeAndAfterAll
    with ResolvedRorSettingsFileProvider {
  this: Suite & BaseSingleNodeEsClusterTest =>

  override lazy val targetEs: EsContainer = SingletonEsContainerWithRorSecurity.singleton.nodes.head

  private var startedDependencies = StartedClusterDependencies(Nil)

  override final def resolvedRorSettingsFile: File = {
    resolveSettings.toTry.get
  }

  override protected def beforeAll(): Unit = {
    startedDependencies = DependencyRunner.startDependencies(clusterDependencies)
    SingletonEsContainerWithRorSecurity.cleanUpContainer()
    SingletonEsContainerWithRorSecurity.updateSettings(resolvedRorSettingsFile.contentAsString)
    nodeDataInitializer.foreach(SingletonEsContainerWithRorSecurity.initNode)
    super.beforeAll()
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    startedDependencies.values.foreach(started => started.container.stop())
  }

  private def resolveSettings: Either[Throwable, File] = {
    Either.cond(
      test = startedDependencies.values.size === clusterDependencies.size,
      right = resolvedSettings(startedDependencies),
      left = new IllegalStateException("Not all dependencies are started. Cannot read resolved settings yet")
    )
  }

  private def resolvedSettings(startedDependencies: StartedClusterDependencies) = {
    val settingsFile = File.apply(getResourcePath(rorSettingsFileName))
    RorSettingsAdjuster.adjustUsingDependencies(settingsFile, startedDependencies)
  }
}