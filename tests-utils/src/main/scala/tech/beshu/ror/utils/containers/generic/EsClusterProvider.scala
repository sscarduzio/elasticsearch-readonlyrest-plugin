package tech.beshu.ror.utils.containers.generic

import cats.data.NonEmptyList
import monix.eval.Task

trait EsClusterProvider extends ClusterProvider {

  override def createLocalClusterContainer(clusterSettings: ClusterSettings) = {
    if (clusterSettings.numberOfInstances < 1) throw new IllegalArgumentException("Cluster should have at least one instance")

    val nodeNames = NonEmptyList.fromListUnsafe(Seq.iterate(1, clusterSettings.numberOfInstances)(_ + 1)
      .toList
      .map(idx => s"${clusterSettings.name}_$idx"))

    new ReadonlyRestEsClusterContainer(
      nodeNames.map { name =>
        val containerConfig = EsWithoutRorPluginContainer.Config(
          nodeName = name,
          nodes = nodeNames,
          esVersion = "7.5.1",
          xPackSupport = clusterSettings.xPackSupport)
        Task(EsWithoutRorPluginContainer.create(containerConfig, clusterSettings.nodeDataInitializer))
      },
      clusterSettings.dependentServicesContainers,
      clusterSettings.clusterInitializer)
  }

  override def createRemoteClustersContainer(localClustersSettings: NonEmptyList[ClusterSettings],
                                             remoteClustersInitializer: RemoteClustersInitializer) = {
    val startedClusters = localClustersSettings.map(createLocalClusterContainer)
    new ReadonlyRestEsRemoteClustersContainer(startedClusters, remoteClustersInitializer)
  }
}
