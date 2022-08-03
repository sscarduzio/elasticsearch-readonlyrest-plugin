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

import better.files._
import cats.data.NonEmptyList
import com.dimafeng.testcontainers.SingleContainer
import org.testcontainers.containers.GenericContainer
import tech.beshu.ror.utils.containers.EsClusterSettings.{ClusterType, EsVersion}
import tech.beshu.ror.utils.containers.exceptions.ContainerCreationException
import tech.beshu.ror.utils.containers.images.{Elasticsearch, ReadonlyRestPlugin, ReadonlyRestWithEnabledXpackSecurityPlugin, XpackSecurityPlugin}
import tech.beshu.ror.utils.gradle.RorPluginGradleProject

import java.io.File

object EsContainerCreator extends EsContainerCreator
trait EsContainerCreator {

  def create(mode: Mode,
             name: String,
             nodeNames: NonEmptyList[String],
             clusterSettings: EsClusterSettings,
             startedClusterDependencies: StartedClusterDependencies): EsContainer = {
    val project = clusterSettings.esVersion match {
      case EsVersion.DeclaredInProject => RorPluginGradleProject.fromSystemProperty
      case EsVersion.SpecificVersion(version) => RorPluginGradleProject.customModule(version)
    }
    clusterSettings.clusterType match {
      case ClusterType.RorWithXpackSecurityCluster(attributes) =>
        createEsWithRorAndXpackSecurityContainer(name, nodeNames, project, clusterSettings, attributes, startedClusterDependencies)
      case ClusterType.RorCluster(attributes) =>
        mode match {
          case Mode.Plugin =>
            createEsWithRorContainer(name, nodeNames, project, clusterSettings, attributes, startedClusterDependencies)
          case Mode.Proxy =>
            createEsWithNoSecurityContainer(name, nodeNames, project, clusterSettings, startedClusterDependencies)
        }
      case ClusterType.XPackSecurityCluster(attributes) =>
        createEsWithXpackContainer(name, nodeNames, project, clusterSettings, attributes, startedClusterDependencies)
      case ClusterType.EsWithNoSecurityCluster =>
        createEsWithNoSecurityContainer(name, nodeNames, project, clusterSettings, startedClusterDependencies)
    }
  }

  private def createEsWithRorAndXpackSecurityContainer(name: String,
                                                       nodeNames: NonEmptyList[String],
                                                       project: RorPluginGradleProject,
                                                       clusterSettings: EsClusterSettings,
                                                       attributes: ReadonlyRestWithEnabledXpackSecurityPlugin.Config.Attributes,
                                                       startedClusterDependencies: StartedClusterDependencies) = {
    val rorPluginFile: File = project.assemble.getOrElse(throw new ContainerCreationException("Plugin file assembly failed"))
    val rawRorConfigFile = ContainerUtils.getResourceFile(attributes.rorConfigFileName)

    val adjustedRorConfig = RorConfigAdjuster.adjustUsingDependencies(
      source = rawRorConfigFile.toScala,
      startedDependencies = startedClusterDependencies,
      mode = Mode.Plugin
    )

    EsContainerWithRorAndXpackSecurity.create(
      esVersion = project.getESVersion,
      esConfig = Elasticsearch.Config(
        clusterName = clusterSettings.name,
        nodeName = name,
        nodes = nodeNames,
        additionalElasticsearchYamlEntries = clusterSettings.rorContainerSpecification.additionalElasticsearchYamlEntries,
        envs = clusterSettings.rorContainerSpecification.environmentVariables
      ),
      securityConfig = ReadonlyRestWithEnabledXpackSecurityPlugin.Config(
        rorPlugin = rorPluginFile.toScala,
        rorConfig = adjustedRorConfig,
        attributes = attributes
      ),
      initializer = clusterSettings.nodeDataInitializer,
      startedClusterDependencies = startedClusterDependencies,
      esClusterSettings = clusterSettings
    )
  }

  private def createEsWithRorContainer(name: String,
                                       nodeNames: NonEmptyList[String],
                                       project: RorPluginGradleProject,
                                       clusterSettings: EsClusterSettings,
                                       attributes: ReadonlyRestPlugin.Config.Attributes,
                                       startedClusterDependencies: StartedClusterDependencies) = {
    val rorPluginFile: File = project.assemble.getOrElse(throw new ContainerCreationException("Plugin file assembly failed"))
    val rawRorConfigFile = ContainerUtils.getResourceFile(attributes.rorConfigFileName)

    val adjustedRorConfig = RorConfigAdjuster.adjustUsingDependencies(
      source = rawRorConfigFile.toScala,
      startedDependencies = startedClusterDependencies,
      mode = Mode.Plugin
    )

    EsContainerWithRorSecurity.create(
      esVersion = project.getESVersion,
      esConfig = Elasticsearch.Config(
        clusterName = clusterSettings.name,
        nodeName = name,
        nodes = nodeNames,
        additionalElasticsearchYamlEntries = clusterSettings.rorContainerSpecification.additionalElasticsearchYamlEntries,
        envs = clusterSettings.rorContainerSpecification.environmentVariables
      ),
      rorConfig = ReadonlyRestPlugin.Config(
        rorPlugin = rorPluginFile.toScala,
        rorConfig = adjustedRorConfig,
        attributes = attributes
      ),
      initializer = clusterSettings.nodeDataInitializer,
      startedClusterDependencies = startedClusterDependencies,
      esClusterSettings = clusterSettings
    )
  }

  private def createEsWithXpackContainer(name: String,
                                         nodeNames: NonEmptyList[String],
                                         project: RorPluginGradleProject,
                                         clusterSettings: EsClusterSettings,
                                         attributes: XpackSecurityPlugin.Config.Attributes,
                                         startedClusterDependencies: StartedClusterDependencies) = {
    EsContainerWithXpackSecurity.create(
      esVersion = project.getESVersion,
      esConfig = Elasticsearch.Config(
        clusterName = clusterSettings.name,
        nodeName = name,
        nodes = nodeNames,
        additionalElasticsearchYamlEntries = clusterSettings.rorContainerSpecification.additionalElasticsearchYamlEntries,
        envs = clusterSettings.rorContainerSpecification.environmentVariables
      ),
      xpackSecurityConfig = XpackSecurityPlugin.Config(attributes),
      initializer = clusterSettings.nodeDataInitializer,
      startedClusterDependencies = startedClusterDependencies,
      esClusterSettings = clusterSettings
    )
  }

  private def createEsWithNoSecurityContainer(name: String,
                                              nodeNames: NonEmptyList[String],
                                              project: RorPluginGradleProject,
                                              clusterSettings: EsClusterSettings,
                                              startedClusterDependencies: StartedClusterDependencies) = {
    EsContainerWithNoSecurity.create(
      esVersion = project.getESVersion,
      esConfig = Elasticsearch.Config(
        clusterName = clusterSettings.name,
        nodeName = name,
        nodes = nodeNames,
        additionalElasticsearchYamlEntries = clusterSettings.rorContainerSpecification.additionalElasticsearchYamlEntries,
        envs = clusterSettings.rorContainerSpecification.environmentVariables
      ),
      initializer = clusterSettings.nodeDataInitializer,
      startedClusterDependencies = startedClusterDependencies,
      esClusterSettings = clusterSettings
    )
  }
}

final case class StartedDependency(name: String, container: SingleContainer[GenericContainer[_]], originalPort: Int)
final case class StartedClusterDependencies(values: List[StartedDependency])