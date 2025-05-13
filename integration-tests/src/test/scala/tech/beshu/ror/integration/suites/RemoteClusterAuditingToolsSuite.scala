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

import tech.beshu.ror.integration.suites.base.BaseAuditingToolsSuite
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.SingletonPluginTestSupport
import tech.beshu.ror.utils.containers.SecurityType.NoSecurityCluster
import tech.beshu.ror.utils.containers.*
import tech.beshu.ror.utils.containers.dependencies.*
import tech.beshu.ror.utils.containers.providers.ClientProvider
import tech.beshu.ror.utils.elasticsearch.BaseManager.JSON
import tech.beshu.ror.utils.elasticsearch.ElasticsearchTweetsInitializer

class RemoteClusterAuditingToolsSuite
  extends BaseAuditingToolsSuite
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport {

  override implicit val rorConfigFileName: String = "/cluster_auditing_tools/readonlyrest.yml"

  private lazy val auditEsContainer: EsContainer = {
    val cluster = createLocalClusterContainer(
      EsClusterSettings.create(
        clusterName = "AUDIT",
        securityType = NoSecurityCluster
      )
    )
    cluster.start()
    cluster.nodes.head
  }

  override def nodeDataInitializer: Option[ElasticsearchNodeDataInitializer] = Some(ElasticsearchTweetsInitializer)

  override def clusterDependencies: List[DependencyDef] = List(es("AUDIT_1", auditEsContainer))

  override lazy val destNodeClientProvider: ClientProvider = auditEsContainer

  override def assertForEveryAuditEntry(entry: JSON): Unit = {
    entry("es_node_name").str shouldBe "ROR_SINGLE_1"
    entry("es_cluster_name").str shouldBe "ROR_SINGLE"
  }
}
