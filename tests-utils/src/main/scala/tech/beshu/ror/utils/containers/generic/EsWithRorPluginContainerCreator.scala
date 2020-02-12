package tech.beshu.ror.utils.containers.generic

import java.io.File

import cats.data.NonEmptyList
import tech.beshu.ror.utils.containers.ContainerUtils
import tech.beshu.ror.utils.containers.exceptions.ContainerCreationException
import tech.beshu.ror.utils.gradle.RorPluginGradleProject

trait EsWithRorPluginContainerCreator extends SingleContainerCreator {

  override def create(name: String,
                      nodeNames: NonEmptyList[String],
                      clusterSettings: ClusterSettings): RorContainer = {
    val project = RorPluginGradleProject.fromSystemProperty
    val rorPluginFile: File = project.assemble.getOrElse(throw new ContainerCreationException("Plugin file assembly failed"))
    val esVersion = project.getESVersion
    val rorConfigFile = ContainerUtils.getResourceFile(clusterSettings.rorConfigFileName)

    val containerConfig = EsWithRorPluginContainer.Config(
      nodeName = name,
      nodes = nodeNames,
      esVersion = esVersion,
      rorPluginFile = rorPluginFile,
      rorConfigFile = rorConfigFile,
      configHotReloadingEnabled = clusterSettings.configHotReloadingEnabled,
      internodeSslEnabled = clusterSettings.internodeSslEnabled,
      xPackSupport = clusterSettings.xPackSupport)
    EsWithRorPluginContainer.create(containerConfig, clusterSettings.nodeDataInitializer)
  }
}
