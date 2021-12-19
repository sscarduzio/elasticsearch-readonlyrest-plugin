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
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.utils.containers.dependencies._
import tech.beshu.ror.utils.containers.providers.ClientProvider
import tech.beshu.ror.utils.containers._
import tech.beshu.ror.utils.elasticsearch.ElasticsearchTweetsInitializer

trait RemoteClusterAuditingToolsSuite
  extends BaseAuditingToolsSuite
    with BaseEsClusterIntegrationTest
    with SingleClientSupport {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/cluster_auditing_tools/readonlyrest_source_es.yml"

  private lazy val auditEsContainer: EsContainer =
    EsWithoutSecurityPluginContainerCreator.create(
      name = "AUDIT_1",
      NonEmptyList.one("AUDIT_1"),
      EsClusterSettings(name = "AUDIT", xPackSupport = false),
      StartedClusterDependencies(List.empty)
    )

  override lazy val clusterContainer: EsClusterContainer = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      nodeDataInitializer = ElasticsearchTweetsInitializer,
      xPackSupport = false,
      dependentServicesContainers = List(es("AUDIT_1", auditEsContainer))
    )
  )

  override lazy val targetEs: EsContainer = container.nodes.head
  override lazy val destNodeClientProvider: ClientProvider = auditEsContainer
}
