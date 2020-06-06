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
import org.scalatest.Matchers._
import org.scalatest.{BeforeAndAfterEach, Entry, WordSpec}
import tech.beshu.ror.integration.suites.base.support.{BaseIntegrationTest, MultipleClientsSupport}
import tech.beshu.ror.integration.utils.{IndexConfigInitializer, PluginTestSupport}
import tech.beshu.ror.utils.containers.EsClusterProvider.ClusterNodeData
import tech.beshu.ror.utils.containers._
import tech.beshu.ror.utils.elasticsearch.ActionManagerJ
import tech.beshu.ror.utils.misc.Resources.getResourceContent

import scala.collection.JavaConverters._

final class LoadClusterConfigsDifferentIndicesTest
  extends WordSpec
    with BeforeAndAfterEach
    with PluginTestSupport
    with BaseIntegrationTest
    with MultipleClientsSupport {

  override implicit val rorConfigFileName = "/two_nodes_two_config_indices/readonlyrest.yml"
  private val readonlyrestIndexName: String = ".readonlyrest"
  private val otherConfigIndexName = ".readonlyrest2"
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
    nodeDataInitializer = new IndexConfigInitializer(otherConfigIndexName, "/two_nodes_two_config_indices/readonlyrest_index2.yml"),
    customRorIndexName = Some(otherConfigIndexName)
  )(rorConfigFileName)
  )
  private lazy val rorWithIndexConfig = createLocalClusterContainers(rorNode1, rorNode2)
  private lazy val ror1WithIndexConfigAdminActionManager = new ActionManagerJ(clients.head.adminClient)

  "return two configs from two indices" in {
    val result = ror1WithIndexConfigAdminActionManager.actionGet("_readonlyrest/admin/config/load")
    result.getResponseCode should be(200)
    result.getResponseJsonMap.get("clusterName") should be("ROR1")
    result.getResponseJsonMap.get("failures").asInstanceOf[util.Collection[Nothing]] should have size 0
    val nodesResult = ror1WithIndexConfigAdminActionManager.actionGet("_nodes")

    def nodeNameById(nodeId: String) = nodesResult.getResponseJsonMap()
      .get("nodes").asInstanceOf[util.Map[String, Any]]
      .get(nodeId).asInstanceOf[util.Map[String, Any]]
      .get("name")

    def findNodeByName(nodeName: String)(node: util.Map[String, String]):Boolean = {
      val nodeId = node.get("nodeId")
      nodeNameById(nodeId) == nodeName
    }

    val javaResponses = result.getResponseJsonMap.get("responses").asInstanceOf[util.List[util.Map[String, String]]].asScala.toList
    val jnode1 = javaResponses.find(findNodeByName("ror1")).get
    jnode1 should contain key "nodeId"
    jnode1 should contain(Entry("type", "IndexConfig"))
    jnode1 should contain(Entry("indexName", readonlyrestIndexName))
    jnode1.get("config") should be(getResourceContent("/two_nodes_two_config_indices/readonlyrest_index1.yml"))
    val jnode2 = javaResponses.find(findNodeByName("ror2")).get
    jnode2 should contain key "nodeId"
    jnode2 should contain(Entry("type", "IndexConfig"))
    jnode2 should contain(Entry("indexName", otherConfigIndexName))
    jnode2.get("config") should be(getResourceContent("/two_nodes_two_config_indices/readonlyrest_index2.yml"))
  }
}
