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
import com.dimafeng.testcontainers.SingleContainer
import monix.eval.Task
import org.scalatest.{BeforeAndAfterAll, Suite}
import org.testcontainers.containers.GenericContainer
import monix.execution.Scheduler.Implicits.global
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.utils.containers.providers._
import tech.beshu.ror.utils.containers.{DependencyDef, EsContainer, EsWithSecurityPluginContainerCreator, RorConfigAdjuster, SingletonEsContainer, StartedClusterDependencies, StartedDependency}
import tech.beshu.ror.utils.misc.Resources.getResourcePath

import scala.collection.immutable.Seq

trait PluginTestSupport extends EsWithSecurityPluginContainerCreator with CallingEsDirectly {
  this: MultipleEsTargets =>
}

trait SingletonPluginTestSupport extends PluginTestSupport with BeforeAndAfterAll {
  this: Suite with BaseSingleNodeEsClusterTest =>

  override lazy val targetEs: EsContainer = SingletonEsContainer.singleton.nodes.head

  private lazy val depsContainers: Seq[(DependencyDef, SingleContainer[GenericContainer[_]])] =
    clusterDependencies.map(d => (d, d.containerCreator.apply()))

  override protected def beforeAll(): Unit = {
    super.beforeAll()

    val startedClusterDependencies = startDependencies()
    val configFile = File.apply(getResourcePath(rorConfigFileName))
    val configAdjusted = RorConfigAdjuster.adjustUsingDependencies(configFile, startedClusterDependencies, RorConfigAdjuster.Mode.Plugin)

    SingletonEsContainer.cleanUpContainer()
    SingletonEsContainer.updateConfig(configAdjusted.contentAsString)
    nodeDataInitializer.foreach(SingletonEsContainer.initNode)
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    depsContainers.foreach(_._2.stop())
  }

  //from EsClusterContainer
  private def startDependencies() = {
    startContainersAsynchronously(depsContainers.map(_._2))
    StartedClusterDependencies {
      depsContainers
        .map { case (dependencyDef, container) =>
          StartedDependency(dependencyDef.name, container, dependencyDef.originalPort)
        }
        .toList
    }
  }

  private def startContainersAsynchronously(containers: Iterable[SingleContainer[_]]): Unit = {
    Task
      .gatherUnordered {
        containers.map(c => Task(c.start()))
      }
      .runSyncUnsafe()
  }

}