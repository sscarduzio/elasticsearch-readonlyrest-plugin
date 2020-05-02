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

import java.util

import cats.data.NonEmptyList
import com.dimafeng.testcontainers.MultipleContainers
import org.apache.commons.lang.StringEscapeUtils.escapeJava
import org.scalatest.Matchers.{be, contain, _}
import org.scalatest.{BeforeAndAfterEach, Entry, WordSpec}
import tech.beshu.ror.integration.suites.base.support.{BaseIntegrationTest, MultipleClientsSupport}
import tech.beshu.ror.utils.containers.EsClusterProvider.ClusterNodeData
import tech.beshu.ror.utils.containers._
import tech.beshu.ror.utils.elasticsearch.{ActionManagerJ, DocumentManagerJ}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.Resources.getResourceContent

final class LoadClusterConfigsDifferentIndicesTest
  extends WordSpec
    with BeforeAndAfterEach
    with PluginTestSupport
    with BaseIntegrationTest
    with MultipleClientsSupport {

  override implicit val rorConfigFileName = "/two_nodes_two_config_indices/readonlyrest.yml"
  private val readonlyrestIndexName: String = ".readonlyrest"
  private lazy val ror1_1Node = rorWithIndexConfig.nodes.head
  private lazy val ror1_2Node = rorWithIndexConfig.nodes.tail.head

  override lazy val esTargets = NonEmptyList.of(ror1_1Node, ror1_2Node)
  override lazy val container: MultipleContainers = MultipleContainers(rorWithIndexConfig)
  private val rorNode1: ClusterNodeData = ClusterNodeData("ror1", EsWithRorPluginContainerCreator, EsClusterSettings(
    name = "ROR1",
    nodeDataInitializer = new IndexConfigInitializer(readonlyrestIndexName, "/two_nodes_two_config_indices/readonlyrest_index1.yml"),
    customRorIndexName = Some(readonlyrestIndexName)
  )(rorConfigFileName)
  )
  private val rorNode2: ClusterNodeData = ClusterNodeData("ror2", EsWithRorPluginContainerCreator, EsClusterSettings(
    name = "ROR1",
    nodeDataInitializer = new IndexConfigInitializer(".readonlyrest2", "/two_nodes_two_config_indices/readonlyrest_index2.yml"),
    customRorIndexName = Some(".readonlyrest2")
  )(rorConfigFileName)
  )
  private lazy val rorWithIndexConfig = createLocalClusterContainers(rorNode1, rorNode2)
  private lazy val ror1WithIndexConfigAdminActionManager = new ActionManagerJ(clients.head.adminClient)

  "in-index config is the same as current one" in {
    val result = ror1WithIndexConfigAdminActionManager.actionGet("_readonlyrest/admin/config/load")
    result.getResponseCode should be(200)
    result.getResponseJsonMap.get("clusterName") should be("ROR1")
    result.getResponseJsonMap.get("failures").asInstanceOf[util.Collection[Nothing]] should have size 1
    val javaResponses = result.getResponseJsonMap.get("responses").asInstanceOf[util.List[util.Map[String, String]]]
    val jnode1 = javaResponses.get(0)
    jnode1 should contain key "nodeId"
    jnode1 should contain(Entry("type", "IndexConfig"))
    jnode1.get("config") should be(getResourceContent("/admin_api/readonlyrest_index.yml"))
  }

}
final class IndexConfigInitializer(readonlyrestIndexName: String, resourceFilePath: String)
  extends ElasticsearchNodeDataInitializer {

  private def insertInIndexConfig(documentManager: DocumentManagerJ, resourceFilePath: String): Unit = {
    documentManager.insertDocAndWaitForRefresh(
      s"/$readonlyrestIndexName/settings/1",
      s"""{"settings": "${escapeJava(getResourceContent(resourceFilePath))}"}"""
    )
  }

  override def initialize(esVersion: String, adminRestClient: RestClient): Unit = {
    val documentManager = new DocumentManagerJ(adminRestClient)
    documentManager.insertDoc("/test1_index/test/1", "{\"hello\":\"world\"}")
    documentManager.insertDoc("/test2_index/test/1", "{\"hello\":\"world\"}")
    insertInIndexConfig(documentManager, resourceFilePath)
  }
}
