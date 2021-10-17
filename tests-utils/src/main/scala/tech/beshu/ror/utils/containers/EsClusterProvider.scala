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
import tech.beshu.ror.utils.containers.EsClusterProvider.ClusterNodeData

trait EsClusterProvider {
  this: EsContainerCreator =>

  def createLocalClusterContainer(esClusterSettings: EsClusterSettings): EsClusterContainer = {
    if (esClusterSettings.numberOfInstances < 1) throw new IllegalArgumentException("Cluster should have at least one instance")
    val nodeNames = NonEmptyList.fromListUnsafe(Seq.iterate(1, esClusterSettings.numberOfInstances)(_ + 1).toList
      .map(idx => s"${esClusterSettings.name}_$idx"))
    val nodesData = nodeNames.map(name => ClusterNodeData(name, esClusterSettings))
    createLocalClusterContainers(nodesData)
  }

  def createLocalClusterContainers(nodesData: ClusterNodeData, nodesDataArgs: ClusterNodeData*): EsClusterContainer =
    createLocalClusterContainers(NonEmptyList.of(nodesData, nodesDataArgs: _*))

  def createLocalClusterContainers(nodesData: NonEmptyList[ClusterNodeData]): EsClusterContainer = {
    val nodeNames = nodesData.map(_.name)
    new EsClusterContainer(
      nodesData.head.settings.rorContainerSpecification,
      nodesData.map(createNode(nodeNames, _)),
      nodesData.head.settings.dependentServicesContainers
    )
  }

  private def createNode(nodeNames: NonEmptyList[String], nodeData: ClusterNodeData) = {
    this.create(nodeData.name, nodeNames, nodeData.settings, _)
  }

  def createRemoteClustersContainer(localClustersSettings: EsClusterSettings,
                                    remoteClustersSettings: NonEmptyList[EsClusterSettings],
                                    remoteClusterSetup: SetupRemoteCluster): EsRemoteClustersContainer = {
    new EsRemoteClustersContainer(
      createLocalClusterContainer(localClustersSettings),
      remoteClustersSettings.map(createLocalClusterContainer),
      remoteClusterSetup
    )
  }

  def createFrom(clusters: NonEmptyList[EsClusterSettings]): EsClusterContainer = {
    def clusterNodeDataFromClusterSettings(esClusterSettings: EsClusterSettings, startingIndex: Int) = {
      Seq.iterate(startingIndex, esClusterSettings.numberOfInstances)(_ + 1)
        .toList
        .map(idx => s"${esClusterSettings.name}_$idx")
        .map(name => ClusterNodeData(name, esClusterSettings))
    }
    val nodesData = clusters
      .toList
      .foldLeft((List.empty[ClusterNodeData], 1)) {
        case ((currentNodeData, startingIndex), settings) =>
          (currentNodeData ::: clusterNodeDataFromClusterSettings(settings, startingIndex),
            startingIndex + settings.numberOfInstances)
      }
    createLocalClusterContainers(NonEmptyList.fromListUnsafe(nodesData._1))
  }
}
object EsClusterProvider {
  final case class ClusterNodeData(name: String, settings: EsClusterSettings)
}
