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
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, BaseManyEsClustersIntegrationTest, MultipleClientsSupport, SingleClientSupport}
import tech.beshu.ror.utils.containers.{ContainerSpecification, EsClusterContainer, EsClusterProvider, EsClusterSettings, EsContainer, EsContainerCreator, _}
import tech.beshu.ror.utils.elasticsearch.CatManager

trait ClusterStateWithInternodeSslSuite
  extends AnyWordSpec
    with BaseEsClusterIntegrationTest
    with SingleClientSupport
    with BeforeAndAfterAll {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/cluster_state_internode_ssl/readonlyrest.yml"


  override def clusterContainer: EsClusterContainer = generalClusterContainer


  override def targetEs: EsContainer = generalClusterContainer.nodes.head


  lazy val generalClusterContainer: EsClusterContainer = createFrom(
    if (executedOn(allEs5x, allEs6xBelowEs63x)) {
      // ROR for ES below 6.3 doesn't support internode SSL with XPack, so we test it only using ROR nodes.
      NonEmptyList.of(
        EsClusterSettings(
          name = "ROR1",
          numberOfInstances = 3,
          internodeSslEnabled = true,
          xPackSupport = false,
        )
      )
    } else {
      NonEmptyList.of(
        EsClusterSettings(
          name = "xpack_cluster",
          numberOfInstances = 2,
          useXpackSecurityInsteadOfRor = true,
          xPackSupport = true,
          externalSslEnabled = false,
          configHotReloadingEnabled = true,
          enableXPackSsl = true
        ),
        EsClusterSettings(
          name = "xpack_cluster",
          internodeSslEnabled = true,
          xPackSupport = false,
          forceNonOssImage = true
        )
      )
    }
  )

  private lazy val rorClusterAdminStateManager = new CatManager(clients.last.rorAdminClient, esVersion = esVersionUsed)

  "Health check" should {
    "be successful" when {
      "internode ssl is enabled" in {
        val response = rorClusterAdminStateManager.healthCheck()

        response.responseCode should be(200)
      }
    }
  }
}

object ElasticWithoutRorClusterProvider extends EsClusterProvider with EsWithoutSecurityPluginContainerCreator