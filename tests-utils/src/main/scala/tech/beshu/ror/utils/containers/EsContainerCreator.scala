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

import better.files.FileExtensions
import cats.data.NonEmptyList
import com.dimafeng.testcontainers.SingleContainer
import org.testcontainers.containers.GenericContainer as JavaGenericContainer
import org.testcontainers.containers.output.OutputFrame
import tech.beshu.ror.utils.containers.EsContainerCreator.EsNodeSettings
import tech.beshu.ror.utils.containers.exceptions.ContainerCreationException
import tech.beshu.ror.utils.containers.images.Elasticsearch.EsInstallationType
import tech.beshu.ror.utils.containers.images.{Elasticsearch, ReadonlyRestPlugin, ReadonlyRestWithEnabledXpackSecurityPlugin, XpackSecurityPlugin}
import tech.beshu.ror.utils.gradle.RorPluginGradleProject
import tech.beshu.ror.utils.misc.OsUtils
import tech.beshu.ror.utils.misc.OsUtils.CurrentOs

import java.io.File
import java.util.function.Consumer

object EsContainerCreator extends EsContainerCreator {

  final case class EsNodeSettings(nodeName: String,
                                  clusterName: String,
                                  securityType: SecurityType,
                                  containerSpecification: ContainerSpecification,
                                  esVersion: EsVersion)
}

trait EsContainerCreator {

  val defaultEsInstallationType: EsInstallationType = OsUtils.currentOs match {
    case CurrentOs.Windows => EsInstallationType.NativeWindowsProcess
    case CurrentOs.OtherThanWindows => EsInstallationType.EsDockerImage
  }

  def create(nodeSettings: EsNodeSettings,
             allNodeNames: NonEmptyList[String],
             nodeDataInitializer: ElasticsearchNodeDataInitializer,
             startedClusterDependencies: StartedClusterDependencies,
             esInstallationType: EsInstallationType = defaultEsInstallationType,
             additionalLogConsumer: Option[Consumer[OutputFrame]] = None): EsContainer = {
    val project = nodeSettings.esVersion match {
      case EsVersion.DeclaredInProject => RorPluginGradleProject.fromSystemProperty
      case EsVersion.SpecificVersion(version) => RorPluginGradleProject.customModule(version)
    }
    nodeSettings.securityType match {
      case SecurityType.RorWithXpackSecurity(attributes) =>
        createEsWithRorAndXpackSecurityContainer(nodeSettings, allNodeNames, project, nodeDataInitializer, attributes, startedClusterDependencies, esInstallationType, additionalLogConsumer)
      case SecurityType.RorSecurity(attributes) =>
        createEsWithRorContainer(nodeSettings, allNodeNames, project, nodeDataInitializer, attributes, startedClusterDependencies, esInstallationType, additionalLogConsumer)
      case SecurityType.XPackSecurity(attributes) =>
        createEsWithXpackContainer(nodeSettings, allNodeNames, project, nodeDataInitializer, attributes, startedClusterDependencies, esInstallationType, additionalLogConsumer)
      case SecurityType.NoSecurityCluster =>
        createEsWithNoSecurityContainer(nodeSettings, allNodeNames, project, nodeDataInitializer, startedClusterDependencies, esInstallationType, additionalLogConsumer)
    }
  }

  private def createEsWithRorAndXpackSecurityContainer(nodeSettings: EsNodeSettings,
                                                       allNodeNames: NonEmptyList[String],
                                                       project: RorPluginGradleProject,
                                                       nodeDataInitializer: ElasticsearchNodeDataInitializer,
                                                       attributes: ReadonlyRestWithEnabledXpackSecurityPlugin.Config.Attributes,
                                                       startedClusterDependencies: StartedClusterDependencies,
                                                       esInstallationType: EsInstallationType,
                                                       additionalLogConsumer: Option[Consumer[OutputFrame]]) = {
    val rorPluginFile: File = project.assemble.getOrElse(throw new ContainerCreationException("Plugin file assembly failed"))
    val rawRorConfigFile = ContainerUtils.getResourceFile(attributes.rorConfigFileName)

    val adjustedRorConfig = RorConfigAdjuster.adjustUsingDependencies(
      source = rawRorConfigFile.toScala,
      startedDependencies = startedClusterDependencies,
    )

    EsContainerWithRorAndXpackSecurity.create(
      esVersion = project.getModuleESVersion,
      esConfig = Elasticsearch.Config(
        clusterName = nodeSettings.clusterName,
        nodeName = nodeSettings.nodeName,
        masterNodes = allNodeNames,
        additionalElasticsearchYamlEntries = nodeSettings.containerSpecification.additionalElasticsearchYamlEntries,
        envs = nodeSettings.containerSpecification.environmentVariables,
        esInstallationType = esInstallationType,
      ),
      securityConfig = ReadonlyRestWithEnabledXpackSecurityPlugin.Config(
        rorPlugin = rorPluginFile.toScala,
        rorConfig = adjustedRorConfig,
        attributes = attributes
      ),
      initializer = nodeDataInitializer,
      startedClusterDependencies = startedClusterDependencies,
      additionalLogConsumer = additionalLogConsumer,
    )
  }

  private def createEsWithRorContainer(nodeSettings: EsNodeSettings,
                                       allNodeNames: NonEmptyList[String],
                                       project: RorPluginGradleProject,
                                       nodeDataInitializer: ElasticsearchNodeDataInitializer,
                                       attributes: ReadonlyRestPlugin.Config.Attributes,
                                       startedClusterDependencies: StartedClusterDependencies,
                                       esInstallationType: EsInstallationType,
                                       additionalLogConsumer: Option[Consumer[OutputFrame]]) = {
    val rorPluginFile: File = project.assemble.getOrElse(throw new ContainerCreationException("Plugin file assembly failed"))
    val rawRorConfigFile = ContainerUtils.getResourceFile(attributes.rorConfigFileName)

    val adjustedRorConfig = RorConfigAdjuster.adjustUsingDependencies(
      source = rawRorConfigFile.toScala,
      startedDependencies = startedClusterDependencies,
    )

    EsContainerWithRorSecurity.create(
      esVersion = project.getModuleESVersion,
      esConfig = Elasticsearch.Config(
        clusterName = nodeSettings.clusterName,
        nodeName = nodeSettings.nodeName,
        masterNodes = allNodeNames,
        additionalElasticsearchYamlEntries = nodeSettings.containerSpecification.additionalElasticsearchYamlEntries,
        envs = nodeSettings.containerSpecification.environmentVariables,
        esInstallationType = esInstallationType,
      ),
      rorConfig = ReadonlyRestPlugin.Config(
        rorPlugin = rorPluginFile.toScala,
        rorConfig = adjustedRorConfig,
        attributes = attributes
      ),
      initializer = nodeDataInitializer,
      startedClusterDependencies = startedClusterDependencies,
      additionalLogConsumer = additionalLogConsumer,
    )
  }

  private def createEsWithXpackContainer(nodeSettings: EsNodeSettings,
                                         allNodeNames: NonEmptyList[String],
                                         project: RorPluginGradleProject,
                                         nodeDataInitializer: ElasticsearchNodeDataInitializer,
                                         attributes: XpackSecurityPlugin.Config.Attributes,
                                         startedClusterDependencies: StartedClusterDependencies,
                                         esInstallationType: EsInstallationType,
                                         additionalLogConsumer: Option[Consumer[OutputFrame]]) = {
    EsContainerWithXpackSecurity.create(
      esVersion = project.getModuleESVersion,
      esConfig = Elasticsearch.Config(
        clusterName = nodeSettings.clusterName,
        nodeName = nodeSettings.nodeName,
        masterNodes = allNodeNames,
        additionalElasticsearchYamlEntries = nodeSettings.containerSpecification.additionalElasticsearchYamlEntries,
        envs = nodeSettings.containerSpecification.environmentVariables,
        esInstallationType = esInstallationType,
      ),
      xpackSecurityConfig = XpackSecurityPlugin.Config(attributes),
      initializer = nodeDataInitializer,
      startedClusterDependencies = startedClusterDependencies,
      additionalLogConsumer = additionalLogConsumer,
    )
  }

  private def createEsWithNoSecurityContainer(nodeSettings: EsNodeSettings,
                                              allNodeNames: NonEmptyList[String],
                                              project: RorPluginGradleProject,
                                              nodeDataInitializer: ElasticsearchNodeDataInitializer,
                                              startedClusterDependencies: StartedClusterDependencies,
                                              esInstallationType: EsInstallationType,
                                              additionalLogConsumer: Option[Consumer[OutputFrame]]) = {
    EsContainerWithNoSecurity.create(
      esVersion = project.getModuleESVersion,
      esConfig = Elasticsearch.Config(
        clusterName = nodeSettings.clusterName,
        nodeName = nodeSettings.nodeName,
        masterNodes = allNodeNames,
        additionalElasticsearchYamlEntries = nodeSettings.containerSpecification.additionalElasticsearchYamlEntries,
        envs = nodeSettings.containerSpecification.environmentVariables,
        esInstallationType = esInstallationType,
      ),
      initializer = nodeDataInitializer,
      startedClusterDependencies = startedClusterDependencies,
      additionalLogConsumer = additionalLogConsumer,
    )
  }
}

final case class StartedDependency(name: String, container: SingleContainer[JavaGenericContainer[_]], originalPort: Int)

final case class StartedClusterDependencies(values: List[StartedDependency])