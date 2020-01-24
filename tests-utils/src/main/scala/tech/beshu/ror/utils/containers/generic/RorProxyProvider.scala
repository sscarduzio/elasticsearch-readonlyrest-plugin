package tech.beshu.ror.utils.containers.generic

import cats.data.NonEmptyList
import monix.eval.Task

object RorProxyProvider {

  def createLocalClusterContainer(name: String,
                                  esVersion: String,
                                  clusterSettings: AdditionalClusterSettings = AdditionalClusterSettings()) = {
    if (clusterSettings.numberOfInstances < 1) throw new IllegalArgumentException("ES Cluster should have at least one instance")

    val nodeNames = NonEmptyList.fromListUnsafe(Seq.iterate(1, clusterSettings.numberOfInstances)(_ + 1).toList.map(idx => s"${name}_$idx"))

    new ReadonlyRestEsClusterContainer(
      nodeNames.map { name =>
        val containerConfig = EsWithoutRorPluginContainer.Config(
          nodeName = name,
          nodes = nodeNames,
          esVersion = esVersion,
          xPackSupport = clusterSettings.xPackSupport)
        Task(EsWithoutRorPluginContainer.create(containerConfig, clusterSettings.nodeDataInitializer))
      },
      clusterSettings.dependentServicesContainers,
      clusterSettings.clusterInitializer)
  }

  def createRemoteClustersContainer(localClusters: NonEmptyList[LocalClusterDef],
                                    remoteClustersInitializer: RemoteClustersInitializer) = {
    val startedClusters = localClusters
      .map { cluster =>
        createLocalClusterContainer(
          cluster.name,
          cluster.rorConfigFileName,
          AdditionalClusterSettings(nodeDataInitializer = cluster.nodeDataInitializer))
      }
    new ReadonlyRestEsRemoteClustersContainer(startedClusters, remoteClustersInitializer)
  }
}
