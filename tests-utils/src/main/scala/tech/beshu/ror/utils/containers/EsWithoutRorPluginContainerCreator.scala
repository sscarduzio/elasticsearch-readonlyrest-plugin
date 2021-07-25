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

import cats.data.NonEmptyList
import tech.beshu.ror.utils.containers.EsClusterSettings.EsVersion
import tech.beshu.ror.utils.gradle.RorPluginGradleProject

trait EsWithoutRorPluginContainerCreator extends EsContainerCreator {

  override def create(name: String,
                      nodeNames: NonEmptyList[String],
                      clusterSettings: EsClusterSettings,
                      startedClusterDependencies: StartedClusterDependencies): EsContainer = {
    val project = clusterSettings.esVersion match {
      case EsVersion.DeclaredInProject => RorPluginGradleProject.fromSystemProperty
      case EsVersion.SpecificVersion(version) => RorPluginGradleProject.customModule(version)
    }
    val esVersion = project.getESVersion

    val containerConfig = EsWithoutRorPluginContainer.Config(
      clusterName = clusterSettings.name,
      nodeName = name,
      nodes = nodeNames,
      envs = clusterSettings.rorContainerSpecification.environmentVariables,
      esVersion = esVersion,
      xPackSupport = clusterSettings.xPackSupport,
      enableFullXPack = clusterSettings.fullXPackSupport,
      customRorIndexName = clusterSettings.customRorIndexName,
      configHotReloadingEnabled = true,
      internodeSslEnabled = false,
      externalSslEnabled = false,
      forceNonOssImage = clusterSettings.forceNonOssImage)

    EsWithoutRorPluginContainer.create(
      containerConfig,
      clusterSettings.nodeDataInitializer,
      startedClusterDependencies,
      clusterSettings
    )
  }
}