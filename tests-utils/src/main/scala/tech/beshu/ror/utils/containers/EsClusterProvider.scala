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

trait EsClusterProvider {
  this: EsContainerCreator =>

  def createLocalClusterContainer(esClusterSettings: EsClusterSettings): EsClusterContainer = {
    if (esClusterSettings.numberOfInstances < 1) throw new IllegalArgumentException("Cluster should have at least one instance")
    val nodeNames = NonEmptyList.fromListUnsafe(Seq.iterate(1, esClusterSettings.numberOfInstances)(_ + 1).toList
      .map(idx => s"${esClusterSettings.name}_$idx"))

    new EsClusterContainer(
      nodeNames.map(name => create(name, nodeNames, esClusterSettings, _)),
      esClusterSettings.dependentServicesContainers
    )
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
}
