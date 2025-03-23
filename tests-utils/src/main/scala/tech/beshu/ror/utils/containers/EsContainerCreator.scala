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

import better.files.*
import cats.data.NonEmptyList
import com.dimafeng.testcontainers.SingleContainer
import org.testcontainers.containers.GenericContainer
import tech.beshu.ror.utils.containers.EsContainerCreator.EsNodeSettings
import tech.beshu.ror.utils.containers.exceptions.ContainerCreationException
import tech.beshu.ror.utils.containers.images.{Elasticsearch, ReadonlyRestPlugin, ReadonlyRestWithEnabledXpackSecurityPlugin, XpackSecurityPlugin}
import tech.beshu.ror.utils.gradle.RorPluginGradleProject
import tech.beshu.ror.utils.gradle.RorPluginGradleProject.PluginFiles

object EsContainerCreator extends EsContainerCreator {

  final case class EsNodeSettings(nodeName: String,
                                  clusterName: String,
                                  securityType: SecurityType,
                                  containerSpecification: ContainerSpecification,
                                  esVersion: EsVersion)
}
trait EsContainerCreator {

  def create(nodeSettings: EsNodeSettings,
             allNodeNames: NonEmptyList[String],
             nodeDataInitializer: ElasticsearchNodeDataInitializer,
             startedClusterDependencies: StartedClusterDependencies): EsContainer = {
    val project = nodeSettings.esVersion match {
      case EsVersion.DeclaredInProject => RorPluginGradleProject.fromSystemProperty
      case EsVersion.SpecificVersion(version) => RorPluginGradleProject.customModule(version)
    }
    nodeSettings.securityType match {
      case SecurityType.RorWithXpackSecurity(attributes, performInstallation) =>
        createEsWithRorAndXpackSecurityContainer(nodeSettings, allNodeNames, project, nodeDataInitializer, attributes, startedClusterDependencies, performInstallation)
      case SecurityType.RorSecurity(attributes) =>
        createEsWithRorContainer(nodeSettings, allNodeNames, project, nodeDataInitializer, attributes, startedClusterDependencies)
      case SecurityType.XPackSecurity(attributes) =>
        createEsWithXpackContainer(nodeSettings, allNodeNames, project, nodeDataInitializer, attributes, startedClusterDependencies)
      case SecurityType.NoSecurityCluster =>
        createEsWithNoSecurityContainer(nodeSettings, allNodeNames, project, nodeDataInitializer, startedClusterDependencies)
    }
  }

  private def createEsWithRorAndXpackSecurityContainer(nodeSettings: EsNodeSettings,
                                                       allNodeNames: NonEmptyList[String],
                                                       project: RorPluginGradleProject,
                                                       nodeDataInitializer: ElasticsearchNodeDataInitializer,
                                                       attributes: ReadonlyRestWithEnabledXpackSecurityPlugin.Config.Attributes,
                                                       startedClusterDependencies: StartedClusterDependencies,
                                                       performInstallation: Boolean) = {
    val pluginFiles: PluginFiles = project.getPluginFiles.getOrElse(throw new ContainerCreationException("Plugin file assembly failed"))
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
        envs = nodeSettings.containerSpecification.environmentVariables
      ),
      securityConfig = ReadonlyRestWithEnabledXpackSecurityPlugin.Config(
        rorPlugin = pluginFiles.plugin.toScala,
        rorProperties = pluginFiles.rorProperties.toScala,
        rorSecurityPolicy = pluginFiles.rorSecurityPolicy.toScala,
        rorConfig = adjustedRorConfig,
        attributes = attributes,
        performInstallation = performInstallation,
      ),
      initializer = nodeDataInitializer,
      startedClusterDependencies = startedClusterDependencies
    )
  }

  private def createEsWithRorContainer(nodeSettings: EsNodeSettings,
                                       allNodeNames: NonEmptyList[String],
                                       project: RorPluginGradleProject,
                                       nodeDataInitializer: ElasticsearchNodeDataInitializer,
                                       attributes: ReadonlyRestPlugin.Config.Attributes,
                                       startedClusterDependencies: StartedClusterDependencies) = {
    val pluginFiles: PluginFiles = project.getPluginFiles.getOrElse(throw new ContainerCreationException("Plugin file assembly failed"))
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
        envs = nodeSettings.containerSpecification.environmentVariables
      ),
      rorConfig = ReadonlyRestPlugin.Config(
        rorPlugin = pluginFiles.plugin.toScala,
        rorProperties = pluginFiles.rorProperties.toScala,
        rorSecurityPolicy = pluginFiles.rorSecurityPolicy.toScala,
        rorConfig = adjustedRorConfig,
        attributes = attributes
      ),
      initializer = nodeDataInitializer,
      startedClusterDependencies = startedClusterDependencies
    )
  }

  private def createEsWithXpackContainer(nodeSettings: EsNodeSettings,
                                         allNodeNames: NonEmptyList[String],
                                         project: RorPluginGradleProject,
                                         nodeDataInitializer: ElasticsearchNodeDataInitializer,
                                         attributes: XpackSecurityPlugin.Config.Attributes,
                                         startedClusterDependencies: StartedClusterDependencies) = {
    EsContainerWithXpackSecurity.create(
      esVersion = project.getModuleESVersion,
      esConfig = Elasticsearch.Config(
        clusterName = nodeSettings.clusterName,
        nodeName = nodeSettings.nodeName,
        masterNodes = allNodeNames,
        additionalElasticsearchYamlEntries = nodeSettings.containerSpecification.additionalElasticsearchYamlEntries,
        envs = nodeSettings.containerSpecification.environmentVariables
      ),
      xpackSecurityConfig = XpackSecurityPlugin.Config(attributes),
      initializer = nodeDataInitializer,
      startedClusterDependencies = startedClusterDependencies
    )
  }

  private def createEsWithNoSecurityContainer(nodeSettings: EsNodeSettings,
                                              allNodeNames: NonEmptyList[String],
                                              project: RorPluginGradleProject,
                                              nodeDataInitializer: ElasticsearchNodeDataInitializer,
                                              startedClusterDependencies: StartedClusterDependencies) = {
    EsContainerWithNoSecurity.create(
      esVersion = project.getModuleESVersion,
      esConfig = Elasticsearch.Config(
        clusterName = nodeSettings.clusterName,
        nodeName = nodeSettings.nodeName,
        masterNodes = allNodeNames,
        additionalElasticsearchYamlEntries = nodeSettings.containerSpecification.additionalElasticsearchYamlEntries,
        envs = nodeSettings.containerSpecification.environmentVariables
      ),
      initializer = nodeDataInitializer,
      startedClusterDependencies = startedClusterDependencies
    )
  }
}

final case class StartedDependency(name: String, container: SingleContainer[GenericContainer[_]], originalPort: Int)
final case class StartedClusterDependencies(values: List[StartedDependency])