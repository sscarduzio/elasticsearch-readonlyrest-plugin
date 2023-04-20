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
import tech.beshu.ror.utils.containers.EsClusterSettings.NodeType
import tech.beshu.ror.utils.containers.EsContainerCreator.EsNodeSettings

import scala.collection.parallel.CollectionConverters._

trait EsClusterProvider extends EsContainerCreator {

  def createLocalClusterContainer(esClusterSettings: EsClusterSettings): EsClusterContainer = {
    val nodesSettings = NonEmptyList.fromListUnsafe {
      esClusterSettings.nodeTypes
        .foldLeft(List.empty[(String, NodeType)]) {
          case (acc, nodeType) =>
            acc ++ List
              .iterate(acc.length + 1, nodeType.numberOfInstances.value)(_ + 1)
              .map(idx => s"${esClusterSettings.clusterName}_$idx")
              .map((_, nodeType))
        }
        .map { case (nodeName, nodeType) =>
          EsNodeSettings(
            nodeName = nodeName,
            clusterName = esClusterSettings.clusterName,
            securityType = nodeType.securityType,
            containerSpecification = esClusterSettings.containerSpecification,
            esVersion = esClusterSettings.esVersion
          )
        }
    }
    val allNodeNames = nodesSettings.map(_.nodeName)
    new EsClusterContainer(
      esClusterSettings,
      nodesSettings.map(nodeCreator(_, allNodeNames, esClusterSettings.nodeDataInitializer)),
      esClusterSettings.dependentServicesContainers
    )
  }

  def createRemoteClustersContainer(localClustersSettings: EsClusterSettings,
                                    remoteClustersSettings: NonEmptyList[EsClusterSettings],
                                    remoteClusterSetup: SetupRemoteCluster): EsRemoteClustersContainer = {
    new EsRemoteClustersContainer(
      createLocalClusterContainer(localClustersSettings),
      NonEmptyList.fromListUnsafe(remoteClustersSettings.toList.par.map(createLocalClusterContainer).toList),
      remoteClusterSetup
    )
  }

  private def nodeCreator(nodeSettings: EsNodeSettings,
                          allNodeNames: NonEmptyList[String],
                          nodeDataInitializer: ElasticsearchNodeDataInitializer): StartedClusterDependencies => EsContainer = { deps =>
    this.create(nodeSettings, allNodeNames, nodeDataInitializer, deps)
  }
}
