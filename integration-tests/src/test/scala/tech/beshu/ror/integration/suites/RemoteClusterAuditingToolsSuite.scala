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
package tech.beshu.ror.integration.suites

import cats.data.NonEmptyList
import tech.beshu.ror.integration.suites.base.BaseAuditingToolsSuite
import tech.beshu.ror.integration.suites.base.support.{BaseManyEsClustersIntegrationTest, MultipleClientsSupport}
import tech.beshu.ror.utils.containers.providers.ClientProvider
import tech.beshu.ror.utils.containers.{EsClusterContainer, EsClusterSettings, EsContainer, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.ElasticsearchTweetsInitializer

trait RemoteClusterAuditingToolsSuite
  extends BaseAuditingToolsSuite
    with BaseManyEsClustersIntegrationTest
    with MultipleClientsSupport {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/cluster_auditing_tools/readonlyrest_source_es.yml"
  private val destRorConfigFileName = "/cluster_auditing_tools/readonlyrest_dest_es.yml"

  private lazy val sourceEsCluster: EsClusterContainer = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      nodeDataInitializer = ElasticsearchTweetsInitializer,
      xPackSupport = false
    )(rorConfigFileName)
  )

  private lazy val destEsCluster: EsClusterContainer = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR2",
      xPackSupport = false
    )(destRorConfigFileName)
  )

  override lazy val clusterContainers: NonEmptyList[EsClusterContainer] = NonEmptyList.of(sourceEsCluster, destEsCluster)
  override lazy val esTargets: NonEmptyList[EsContainer] = NonEmptyList.of(sourceEsCluster.nodes.head, destEsCluster.nodes.head)

  override lazy val sourceNodeClientProvider: ClientProvider = clients.head
  override lazy val destNodeClientProvider: ClientProvider = clients.last

}
