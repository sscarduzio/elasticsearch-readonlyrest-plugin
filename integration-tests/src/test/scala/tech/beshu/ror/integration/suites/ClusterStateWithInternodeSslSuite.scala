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
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.integration.utils.ESVersionSupportForAnyWordSpecLike
import tech.beshu.ror.utils.containers.{EsClusterContainer, EsClusterProvider, EsClusterSettings, EsContainer, EsContainerCreator, _}
import tech.beshu.ror.utils.elasticsearch.{CatManager, RorApiManager}
import tech.beshu.ror.utils.misc.Resources.getResourceContent

trait ClusterStateWithInternodeSslSuite
  extends AnyWordSpec
    with BaseEsClusterIntegrationTest
    with ESVersionSupportForAnyWordSpecLike
    with SingleClientSupport
    with BeforeAndAfterAll {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/cluster_state_internode_ssl/readonlyrest.yml"

  override def clusterContainer: EsClusterContainer = generalClusterContainer

  override def targetEs: EsContainer = generalClusterContainer.nodes.head

  lazy val generalClusterContainer: EsClusterContainer = createFrom(
    if (executedOn(allEs6xExceptEs67x)) {
      // ROR for ES below 6.7 doesn't support internode SSL with XPack, so we test it only using ROR nodes.
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
          internodeSslEnabled = true,
          xPackSupport = false,
          forceNonOssImage = true
        ),
        EsClusterSettings(
          name = "xpack_cluster",
          numberOfInstances = 2,
          useXpackSecurityInsteadOfRor = true,
          xPackSupport = true,
          externalSslEnabled = false,
          configHotReloadingEnabled = true,
          enableXPackSsl = true
        )
      )
    }
  )

  "Health check" should {
    "be successful" when {
      "internode ssl is enabled" in {
        val rorClusterAdminStateManager = new CatManager(clusterContainer.nodes.head.rorAdminClient, esVersion = esVersionUsed)

        val response = rorClusterAdminStateManager.healthCheck()

        response.responseCode should be(200)
      }
      "ROR config reload can be done" in {
        val rorApiManager = new RorApiManager(clusterContainer.nodes.head.rorAdminClient, esVersion = esVersionUsed)

        val result = rorApiManager
          .updateRorInIndexConfig(getResourceContent("/cluster_state_internode_ssl/readonlyrest_update.yml"))

        result.responseCode should be(200)
        result.responseJson("status").str should be("ok")

        val user1CatManager = new CatManager(basicAuthClient("user1", "test"), esVersion = esVersionUsed)

        val response = user1CatManager.healthCheck()

        response.responseCode should be(200)
      }
    }
  }
}

object ElasticWithoutRorClusterProvider extends EsClusterProvider with EsWithoutSecurityPluginContainerCreator