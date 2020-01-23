package tech.beshu.ror.utils.containers

import cats.data.NonEmptyList
import monix.eval.Task
import tech.beshu.ror.utils.containers.ReadonlyRestEsClusterContainerGeneric.AdditionalClusterSettings

object RorProxyProvider {

  def provideNodes(clusterName: String,
                   esVersion: String,
                   clusterSettings: AdditionalClusterSettings = AdditionalClusterSettings()) = {
    if (clusterSettings.numberOfInstances < 1) throw new IllegalArgumentException("ES Cluster should have at least one instance")

    val nodeNames = NonEmptyList.fromListUnsafe(Seq.iterate(1, clusterSettings.numberOfInstances)(_ + 1).toList.map(idx => s"${clusterName}_$idx"))

    nodeNames.map { name =>
      val containerConfig = EsWithoutRorPluginContainer.Config(
        nodeName = name,
        nodes = nodeNames,
        esVersion = esVersion,
        xPackSupport = clusterSettings.xPackSupport)
      Task(EsWithoutRorPluginContainer.create(containerConfig, clusterSettings.nodeDataInitializer))
    }
  }
}
