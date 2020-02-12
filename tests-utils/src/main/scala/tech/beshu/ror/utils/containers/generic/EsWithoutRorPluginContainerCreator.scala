package tech.beshu.ror.utils.containers.generic

import cats.data.NonEmptyList
import tech.beshu.ror.utils.gradle.RorPluginGradleProject

trait EsWithoutRorPluginContainerCreator extends SingleContainerCreator {

  override def create(name: String,
                      nodeNames: NonEmptyList[String],
                      clusterSettings: ClusterSettings): RorContainer = {
    val project = RorPluginGradleProject.fromSystemProperty
    val esVersion = project.getESVersion

    val containerConfig = EsWithoutRorPluginContainer.Config(
      nodeName = name,
      nodes = nodeNames,
      esVersion = esVersion,
      xPackSupport = clusterSettings.xPackSupport)
    EsWithoutRorPluginContainer.create(containerConfig, clusterSettings.nodeDataInitializer)
  }
}
