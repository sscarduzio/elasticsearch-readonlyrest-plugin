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
import scala.collection.JavaConverters._
import cats.data.NonEmptyList
import com.dimafeng.testcontainers.MultipleContainers
import org.scalatest.Matchers.{be, contain, _}
import org.scalatest.{BeforeAndAfterEach, Entry, WordSpec}
import tech.beshu.ror.integration.suites.base.support.{BaseIntegrationTest, MultipleClientsSupport}
import tech.beshu.ror.integration.utils.IndexConfigInitializer
import tech.beshu.ror.utils.containers.EsClusterProvider.ClusterNodeData
import tech.beshu.ror.utils.containers._
import tech.beshu.ror.utils.elasticsearch.ActionManagerJ
import tech.beshu.ror.utils.misc.Resources.getResourceContent

final class LoadClusterConfigsWithTwoRorNodeTest
  extends WordSpec
    with BeforeAndAfterEach
    with PluginTestSupport
    with BaseIntegrationTest
    with MultipleClientsSupport {
  this: EsContainerCreator =>
  override implicit val rorConfigFileName = "/admin_api/readonlyrest.yml"
  private val readonlyrestIndexName: String = ".readonlyrest"
  private lazy val ror1_1Node = rorWithIndexConfig.nodes.head
  private lazy val ror1_2Node = rorWithIndexConfig.nodes.tail.head
  override lazy val esTargets = NonEmptyList.of(ror1_1Node, ror1_2Node)
  override lazy val container: MultipleContainers = MultipleContainers(rorWithIndexConfig)

  private val clusterSettings: EsClusterSettings = EsClusterSettings(
    name = "ROR1",
    nodeDataInitializer = new IndexConfigInitializer(readonlyrestIndexName, "/admin_api/readonlyrest_index.yml")
  )(rorConfigFileName)
  private val rorNode1: ClusterNodeData = ClusterNodeData("ror1", EsWithRorPluginContainerCreator, clusterSettings)
  private val rorNode2: ClusterNodeData = ClusterNodeData("ror2", EsWithRorPluginContainerCreator, clusterSettings)
  private lazy val rorWithIndexConfig = createLocalClusterContainers(rorNode1, rorNode2)

  private lazy val ror1WithIndexConfigAdminActionManager = new ActionManagerJ(clients.head.adminClient)

  "return exactly same config two times" in {
    val result = ror1WithIndexConfigAdminActionManager.actionGet("_readonlyrest/admin/config/load")
    result.getResponseCode should be(200)
    result.getResponseJsonMap.get("clusterName") should be("ROR1")
    result.getResponseJsonMap.get("failures").asInstanceOf[util.Collection[Nothing]] shouldBe empty
    val javaResponses = result.getResponseJsonMap.get("responses").asInstanceOf[util.List[util.Map[String, String]]]
    val jnode1 = javaResponses.get(0)
    jnode1 should contain key "nodeId"
    jnode1 should contain(Entry("type", "IndexConfig"))
    jnode1 should contain(Entry("indexName", readonlyrestIndexName))
    jnode1.get("config") should be(getResourceContent("/admin_api/readonlyrest_index.yml"))
    val jnode2 = javaResponses.get(1)
    jnode2 should contain key "nodeId"
    jnode2 should contain(Entry("type", "IndexConfig"))
    jnode2 should contain(Entry("indexName", readonlyrestIndexName))
    jnode2.get("config") should be(getResourceContent("/admin_api/readonlyrest_index.yml"))
  }
  "return two timeouts" in {
    val result = ror1WithIndexConfigAdminActionManager.actionGet("_readonlyrest/admin/config/load", Map("timeout" -> "1 nanos").asJava)
    result.getResponseCode should be(200)
    result.getResponseJsonMap.get("clusterName") should be("ROR1")
    result.getResponseJsonMap.get("responses").asInstanceOf[util.List[util.Map[String, String]]] shouldBe empty
    val javaResponsesFailures = result.getResponseJsonMap.get("failures").asInstanceOf[util.List[util.Map[String, String]]]
    val failure1 = javaResponsesFailures.get(0)
    failure1 should contain key "nodeId"
    failure1 should contain key "detailedMessage"
    val failure2 = javaResponsesFailures.get(1)
    failure2 should contain key "nodeId"
    failure1 should contain key "detailedMessage" }
  "return summary" in {
    val result = ror1WithIndexConfigAdminActionManager.actionGet("_readonlyrest/admin/config/load")
    result.getResponseCode should be(200)
    result.getResponseJsonMap.get("clusterName") should be("ROR1")
    val summary = result.getResponseJsonMap.get("summary").asInstanceOf[util.Map[String, Any]]
    summary.get("type") shouldEqual "clear_result"
    val javaResponses = result.getResponseJsonMap.get("responses").asInstanceOf[util.List[util.Map[String, String]]]
    val javaResponse1 = javaResponses.get(0).asInstanceOf[util.Map[String, Any]]
    javaResponse1.remove("nodeId")
    summary.get("value") shouldEqual javaResponse1

  }
}
