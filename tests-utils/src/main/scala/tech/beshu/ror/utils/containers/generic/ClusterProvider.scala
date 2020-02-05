package tech.beshu.ror.utils.containers.generic

import cats.data.NonEmptyList

trait ClusterProvider {

  def createLocalClusterContainer(clusterSettings: ClusterSettings): ReadonlyRestEsClusterContainer

  def createRemoteClustersContainer(localClustersSettings: NonEmptyList[ClusterSettings],
                                    remoteClustersInitializer: RemoteClustersInitializer): ReadonlyRestEsRemoteClustersContainer
}
