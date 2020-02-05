package tech.beshu.ror.utils.containers.generic

import cats.data.NonEmptyList

trait EsContainerCreator extends SingleContainerCreator {

  override def create(name: String,
                      nodeNames: NonEmptyList[String],
                      clusterSettings: ClusterSettings): RorContainer = {
    val containerConfig = EsWithoutRorPluginContainer.Config(
      nodeName = name,
      nodes = nodeNames,
      esVersion = "7.5.1",
      xPackSupport = clusterSettings.xPackSupport)
    EsWithoutRorPluginContainer.create(containerConfig, clusterSettings.nodeDataInitializer)
  }
}
