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
import tech.beshu.ror.utils.containers._
import tech.beshu.ror.utils.elasticsearch._
import tech.beshu.ror.utils.misc.Resources.getResourceContent

trait XpackClusterWithRorNodesAndInternodeSslSuite
  extends AnyWordSpec
    with BaseEsClusterIntegrationTest
    with ESVersionSupportForAnyWordSpecLike
    with SingleClientSupport
    with BeforeAndAfterAll {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/xpack_cluster_with_ror_nodes_and_internode_ssl/readonlyrest.yml"

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

  "Health check works" in {
    val rorClusterAdminStateManager = new CatManager(clusterContainer.nodes.head.rorAdminClient, esVersion = esVersionUsed)

    val response = rorClusterAdminStateManager.healthCheck()

    response.responseCode should be(200)
  }
  "ROR config reload can be done" in {
    val rorApiManager = new RorApiManager(clusterContainer.nodes.head.rorAdminClient, esVersion = esVersionUsed)

    val updateResult = rorApiManager
      .updateRorInIndexConfig(getResourceContent("/xpack_cluster_with_ror_nodes_and_internode_ssl/readonlyrest_update.yml"))

    updateResult.responseCode should be(200)
    updateResult.responseJson("status").str should be("ok")

    val indexManager = new IndexManager(basicAuthClient("user1", "test"), esVersion = esVersionUsed)
    indexManager.createIndex("test").force()

    val getIndexResult = indexManager.getIndex("test")

    getIndexResult.responseCode should be(200)
    getIndexResult.indicesAndAliases.keys.toList should be(List("test"))
  }
  "Field caps request works" in {
    val documentManager = new DocumentManager(clusterContainer.nodes.head.rorAdminClient, esVersion = esVersionUsed)
    documentManager.createDoc("user2_index", 1, ujson.read("""{ "data1": 1, "data2": 2 }"""))

    val searchManager = new SearchManager(basicAuthClient("user2", "test"))
    val result = searchManager.fieldCaps(indices = "user2_index" :: Nil, fields = "data1" :: Nil)

    result.responseCode should be(200)
  }
}