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
package tech.beshu.ror.utils.containers.generic

import cats.data.NonEmptyList
import monix.eval.Task

trait ClusterProvider {
  this: SingleContainerCreator =>

  def createLocalClusterContainer(clusterSettings: ClusterSettings): ClusterContainer = {
    if (clusterSettings.numberOfInstances < 1) throw new IllegalArgumentException("Cluster should have at least one instance")
    val nodeNames = NonEmptyList.fromListUnsafe(Seq.iterate(1, clusterSettings.numberOfInstances)(_ + 1).toList
      .map(idx => s"${clusterSettings.name}_$idx"))

    new ClusterContainer(
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
