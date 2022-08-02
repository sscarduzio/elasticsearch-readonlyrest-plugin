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
package tech.beshu.ror.integration.plugin

import cats.data.NonEmptyList
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.plugin.LoadClusterConfigsWithNoRorNodeTest.IndexConfigInitializer
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, MultipleClientsSupport}
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyWordSpecLike, PluginTestSupport}
import tech.beshu.ror.utils.containers.EsClusterProvider.ClusterNodeData
import tech.beshu.ror.utils.containers._
import tech.beshu.ror.utils.elasticsearch.RorApiManager
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.Resources.getResourceContent

final class LoadClusterConfigsWithNoRorNodeTest
  extends AnyWordSpec
    with BeforeAndAfterEach
    with PluginTestSupport
    with BaseEsClusterIntegrationTest
    with MultipleClientsSupport
    with ESVersionSupportForAnyWordSpecLike {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/admin_api/readonlyrest.yml"

  private val readonlyrestIndexName: String = ".readonlyrest"

  private lazy val ror1_1Node = container.nodes.head
  private lazy val ror1_2Node = container.nodes.tail.head

  override lazy val esTargets = NonEmptyList.of(ror1_1Node, ror1_2Node)

  override def clusterContainer: EsClusterContainer = createLocalClusterContainers(rorNode1, rorNode2)

  private lazy val rorNode1: ClusterNodeData = ClusterNodeData(
    name = "ror1",
    settings = EsClusterSettings(
      name = "ROR1",
      nodeDataInitializer = IndexConfigInitializer,
      numberOfInstances = 2
    )
  )
  private lazy val rorNode2: ClusterNodeData = ClusterNodeData(
    name = "ror2",
    settings = EsClusterSettings(
      name = "ROR1",
      nodeDataInitializer = IndexConfigInitializer
    )
  )

  private lazy val ror1WithIndexConfigAdminActionManager = new RorApiManager(clients.head.adminClient, esVersionUsed)

  "return index config, and a failure" excludeES (allEs8x) in {
    val result = ror1WithIndexConfigAdminActionManager.loadRorCurrentConfig()

    result.responseCode should be(200)
    val config = result.responseJson("config")
    config("indexName").str should be(readonlyrestIndexName)
    config("type").str should be("INDEX_CONFIG")
    config("raw").str should be(getResourceContent("/admin_api/readonlyrest_index.yml"))
    result.responseJson("warnings").arr.toList shouldBe Nil
  }
  "return timeout" in {
    val result = ror1WithIndexConfigAdminActionManager.loadRorCurrentConfig(Map("timeout" -> "1nanos"))

    result.responseCode should be(200)
    result.responseJson("config").isNull should be(true)
    result.responseJson("warnings").arr.toList shouldBe Nil
    result.responseJson("error").str shouldBe "current node response timeout"
  }

}

object LoadClusterConfigsWithNoRorNodeTest {

  private object IndexConfigInitializer extends ElasticsearchNodeDataInitializer {

    override def initialize(esVersion: String, adminRestClient: RestClient): Unit = {
      val rorApiManager = new RorApiManager(adminRestClient, esVersion)
      rorApiManager
        .updateRorInIndexConfig(getResourceContent("/admin_api/readonlyrest_index.yml"))
        .force()
    }
  }

}