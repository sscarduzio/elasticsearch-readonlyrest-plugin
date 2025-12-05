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

import better.files.FileExtensions
import cats.data.NonEmptyList
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.atomic.AtomicInt
import os.Path
import tech.beshu.ror.utils.containers.*
import tech.beshu.ror.utils.containers.ElasticsearchNodeWaitingStrategy.AwaitingReadyStrategy
import tech.beshu.ror.utils.containers.EsContainerCreator.EsNodeSettings
import tech.beshu.ror.utils.containers.exceptions.ContainerCreationException
import tech.beshu.ror.utils.containers.images.domain.Enabled
import tech.beshu.ror.utils.containers.images.{Elasticsearch, ReadonlyRestWithEnabledXpackSecurityPlugin}
import tech.beshu.ror.utils.containers.windows.{WindowsEsDirectoryManager, WindowsEsSetup}
import tech.beshu.ror.utils.gradle.RorPluginGradleProject
import tech.beshu.ror.utils.misc.OsUtils
import tech.beshu.ror.utils.misc.OsUtils.CurrentOs

import java.io.{File, Console as _}
import scala.concurrent.duration.*
import scala.language.postfixOps

// This class encapsulates and manages custom ES container needed by :
// - with installed ROR plugin, but without patching ES
// - without starting ES (by using custom entrypoint) - it would not start without applying patches first
class ExampleEsWithRorContainer(implicit scheduler: Scheduler) extends EsContainerCreator {

  private val uniqueClusterId: AtomicInt = AtomicInt(1)

  private lazy val esContainer: EsContainer = createEsContainer

  def withTestEsContainer(withStartedEs: EsContainer => Unit): Unit = {
    try {
      Task.delay(esContainer.start())
        .map(_ => withStartedEs(esContainer))
        .runSyncUnsafe(5 minutes)
    } finally {
      Task.delay(esContainer.stop()).runSyncUnsafe()
    }
  }

  def windowsBasedEsPath: Path = {
    WindowsEsSetup.prepareEs(esContainer.elasticsearch)
    WindowsEsDirectoryManager.esPath(esContainer.esConfig.clusterName, esContainer.esConfig.nodeName)
  }

  private def createEsContainer: EsContainer = {
    val clusterName = s"ROR_${uniqueClusterId.getAndIncrement()}"
    val nodeName = s"${clusterName}_1"
    val attributes = ReadonlyRestWithEnabledXpackSecurityPlugin.Config.Attributes.default.copy(
      rorConfigReloading = Enabled.No,
      rorConfigFileName = "/basic/readonlyrest.yml",
    )
    createCustomES(
      nodeSettings = EsNodeSettings(
        nodeName = nodeName,
        clusterName = clusterName,
        securityType = SecurityType.RorWithXpackSecurity(attributes),
        containerSpecification = ContainerSpecification.empty,
        esVersion = EsVersion.DeclaredInProject
      ),
      allNodeNames = NonEmptyList.of(nodeName),
      nodeDataInitializer = NoOpElasticsearchNodeDataInitializer,
      attributes = attributes,
      startedClusterDependencies = StartedClusterDependencies(List.empty)
    )
  }

  private def createCustomES(nodeSettings: EsNodeSettings,
                             allNodeNames: NonEmptyList[String],
                             nodeDataInitializer: ElasticsearchNodeDataInitializer,
                             attributes: ReadonlyRestWithEnabledXpackSecurityPlugin.Config.Attributes,
                             startedClusterDependencies: StartedClusterDependencies) = {
    val project = RorPluginGradleProject.fromSystemProperty
    val pluginFile: File = project.assemble.getOrElse(throw new ContainerCreationException("Plugin not assembled, build the plugin or run the test from Gradle"))
    val rawRorConfigFile = ContainerUtils.getResourceFile(attributes.rorConfigFileName)

    val adjustedRorConfig = RorConfigAdjuster.adjustUsingDependencies(
      source = rawRorConfigFile.toScala,
      startedDependencies = startedClusterDependencies,
    )

    EsContainerWithRorAndXpackSecurity.createWithPatchingDisabled(
      esVersion = project.getModuleESVersion,
      esConfig = Elasticsearch.Config(
        clusterName = nodeSettings.clusterName,
        nodeName = nodeSettings.nodeName,
        masterNodes = allNodeNames,
        additionalElasticsearchYamlEntries = nodeSettings.containerSpecification.additionalElasticsearchYamlEntries,
        envs = nodeSettings.containerSpecification.environmentVariables,
        esInstallationType = EsContainerCreator.defaultEsInstallationType,
      ),
      securityConfig = ReadonlyRestWithEnabledXpackSecurityPlugin.Config(
        rorPlugin = pluginFile.toScala,
        rorConfig = adjustedRorConfig,
        attributes = attributes,
      ),
      initializer = nodeDataInitializer,
      startedClusterDependencies = startedClusterDependencies,
      customEntrypoint = OsUtils.currentOs match {
        case CurrentOs.Windows =>
          None // On Windows we prepare and configure ES, but we do not start it
        case CurrentOs.OtherThanWindows =>
          Some(Path("""/bin/sh -c "while true; do sleep 30; done"""")) // On Linux we need to start the container, but not ES
      },
      awaitingReadyStrategy = AwaitingReadyStrategy.ImmediatelyTreatAsReady,
      additionalLogConsumer = None,
    )
  }
}