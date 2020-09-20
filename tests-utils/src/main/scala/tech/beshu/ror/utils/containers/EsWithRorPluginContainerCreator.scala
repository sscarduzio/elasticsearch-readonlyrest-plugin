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

import java.io.File

import better.files._
import cats.data.NonEmptyList
import tech.beshu.ror.utils.containers.exceptions.ContainerCreationException
import tech.beshu.ror.utils.gradle.RorPluginGradleProject

trait EsWithRorPluginContainerCreator extends EsContainerCreator {

  override def create(name: String,
                      nodeNames: NonEmptyList[String],
                      clusterSettings: EsClusterSettings,
                      startedClusterDependencies: StartedClusterDependencies): EsContainer = {
    val project = RorPluginGradleProject.fromSystemProperty
    val rorPluginFile: File = project.assemble.getOrElse(throw new ContainerCreationException("Plugin file assembly failed"))
    val esVersion = project.getESVersion
    val rawRorConfigFile = ContainerUtils.getResourceFile(clusterSettings.rorConfigFileName)

    val adjustedRorConfig = RorConfigAdjuster.adjustUsingDependencies(
      rawRorConfigFile.toScala,
      startedClusterDependencies,
      RorConfigAdjuster.Mode.Plugin
    )

    val containerConfig = EsWithRorPluginContainer.Config(
      clusterName = clusterSettings.name,
      nodeName = name,
      nodes = nodeNames,
      envs = clusterSettings.rorContainerSpecification.environmentVariables,
      esVersion = esVersion,
      rorPluginFile = rorPluginFile,
      rorConfigFile = adjustedRorConfig.toJava,
      configHotReloadingEnabled = clusterSettings.configHotReloadingEnabled,
      customRorIndexName = clusterSettings.customRorIndexName,
      internodeSslEnabled = clusterSettings.internodeSslEnabled,
      xPackSupport = clusterSettings.xPackSupport,
      externalSslEnabled = true)

    EsWithRorPluginContainer.create(
      containerConfig,
      clusterSettings.nodeDataInitializer,
      startedClusterDependencies,
      clusterSettings)
  }
}
object EsWithRorPluginContainerCreator
  extends EsWithRorPluginContainerCreator
    with NoXpackSupport
