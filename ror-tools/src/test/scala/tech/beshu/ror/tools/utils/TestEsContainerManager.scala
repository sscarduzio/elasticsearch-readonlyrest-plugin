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
package tech.beshu.ror.tools.utils

import cats.data.NonEmptyList
import monix.eval.Task
import monix.execution.atomic.AtomicInt
import tech.beshu.ror.utils.containers.*
import tech.beshu.ror.utils.containers.EsContainerCreator.EsNodeSettings
import tech.beshu.ror.utils.containers.images.ReadonlyRestWithEnabledXpackSecurityPlugin
import tech.beshu.ror.utils.containers.images.domain.Enabled

import java.io.Console as _
import scala.concurrent.duration.*
import scala.language.postfixOps

class TestEsContainerManager extends EsContainerCreator {

  private val uniqueClusterId: AtomicInt = AtomicInt(1)

  lazy val esContainer: EsContainer = createEsContainer

  def start(): Task[Unit] = Task.delay(esContainer.start())

  def stop(): Task[Unit] = Task.delay(esContainer.stop())

  private def createEsContainer: EsContainer = {
    val clusterName = s"ROR_${uniqueClusterId.getAndIncrement()}"
    val nodeName = s"${clusterName}_1"
    create(
      nodeSettings = EsNodeSettings(
        nodeName = nodeName,
        clusterName = clusterName,
        securityType = SecurityType.RorWithXpackSecurity(
          ReadonlyRestWithEnabledXpackSecurityPlugin.Config.Attributes.default.copy(
            rorConfigReloading = Enabled.Yes(1 hour),
            rorConfigFileName = "/basic/readonlyrest.yml",
          ),
          performInstallation = false,
        ),
        containerSpecification = ContainerSpecification.empty,
        esVersion = EsVersion.DeclaredInProject
      ),
      allNodeNames = NonEmptyList.of(nodeName),
      nodeDataInitializer = NoOpElasticsearchNodeDataInitializer,
      startedClusterDependencies = StartedClusterDependencies(List.empty)
    )
  }
}