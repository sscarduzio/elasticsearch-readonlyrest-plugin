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
import org.scalatest.Matchers.{be, contain, _}
import org.scalatest.{BeforeAndAfterEach, Entry, WordSpec}
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, MultipleClientsSupport}
import tech.beshu.ror.integration.utils.{IndexConfigInitializer, PluginTestSupport}
import tech.beshu.ror.utils.containers.EsClusterProvider.ClusterNodeData
import tech.beshu.ror.utils.containers._
import tech.beshu.ror.utils.elasticsearch.ActionManagerJ
import tech.beshu.ror.utils.misc.Resources.getResourceContent

final class LoadClusterConfigsWithNoRorNodeTest
  extends WordSpec
    with BeforeAndAfterEach
    with PluginTestSupport
    with BaseEsClusterIntegrationTest
    with MultipleClientsSupport {
  this: EsContainerCreator =>
  override implicit val rorConfigFileName = "/admin_api/readonlyrest.yml"
  private val readonlyrestIndexName: String = ".readonlyrest"
  private lazy val ror1_1Node = container.nodes.head
  private lazy val ror1_2Node = container.nodes.tail.head
  override lazy val esTargets = NonEmptyList.of(ror1_1Node, ror1_2Node)

  override def clusterContainer: EsClusterContainer = createLocalClusterContainers(rorNode1, rorNode2)

  private val rorNode1: ClusterNodeData = ClusterNodeData("ror1", EsWithRorPluginContainerCreator, EsClusterSettings(
    name = "ROR1",
    nodeDataInitializer = new IndexConfigInitializer(readonlyrestIndexName, "/admin_api/readonlyrest_index.yml")
  )(rorConfigFileName)
  )
  private val rorNode2: ClusterNodeData = ClusterNodeData("ror2", EsWithoutRorPluginContainerCreator, EsClusterSettings(
    name = "ROR1",
    nodeDataInitializer = new IndexConfigInitializer(readonlyrestIndexName, "/admin_api/readonlyrest_index.yml")
  )(rorConfigFileName)
  )


  private lazy val ror1WithIndexConfigAdminActionManager = new ActionManagerJ(clients.head.adminClient)

  "return index config, and a failure" in {
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
