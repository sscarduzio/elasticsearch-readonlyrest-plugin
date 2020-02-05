package tech.beshu.ror.utils.containers.generic

import cats.data.NonEmptyList
import monix.eval.Task

trait ClusterProvider {
  this: SingleContainerCreator =>

  def createLocalClusterContainer(clusterSettings: ClusterSettings): ReadonlyRestEsClusterContainer = {
    if (clusterSettings.numberOfInstances < 1) throw new IllegalArgumentException("Cluster should have at least one instance")
    val nodeNames = NonEmptyList.fromListUnsafe(Seq.iterate(1, clusterSettings.numberOfInstances)(_ + 1).toList
      .map(idx => s"${clusterSettings.name}_$idx"))

    new ReadonlyRestEsClusterContainer(
      nodeNames.map(name => Task(create(name, nodeNames, clusterSettings))),
      clusterSettings.dependentServicesContainers,
      clusterSettings.clusterInitializer)
  }

  def createRemoteClustersContainer(localClustersSettings: NonEmptyList[ClusterSettings],
                                    remoteClustersInitializer: RemoteClustersInitializer) = {
    val startedClusters = localClustersSettings.map(createLocalClusterContainer)
    new ReadonlyRestEsRemoteClustersContainer(startedClusters, remoteClustersInitializer)
  }
}
