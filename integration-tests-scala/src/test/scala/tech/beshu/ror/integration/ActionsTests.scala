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
package tech.beshu.ror.integration

import com.dimafeng.testcontainers.ForAllTestContainer
import org.junit.Assert.assertEquals
import org.scalatest.WordSpec
import tech.beshu.ror.utils.containers.ReadonlyRestEsCluster.AdditionalClusterSettings
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, ReadonlyRestEsCluster, ReadonlyRestEsClusterContainer}
import tech.beshu.ror.utils.elasticsearch.{ActionManagerJ, DocumentManagerJ}
import tech.beshu.ror.utils.httpclient.RestClient

class ActionsTests extends WordSpec with ForAllTestContainer {

  override val container: ReadonlyRestEsClusterContainer = ReadonlyRestEsCluster.createLocalClusterContainer(
    name = "ROR1",
    rorConfigFileName = "/actions/readonlyrest.yml",
    clusterSettings = AdditionalClusterSettings(
      nodeDataInitializer = ActionsTests.nodeDataInitializer()
    )
  )

  private lazy val actionManager = new ActionManagerJ(container.nodesContainers.head.client("any", "whatever"))

  "A actions rule" should {
    "work for delete request" which {
      "forbid deleting from test1_index" in {
        val result = actionManager.actionDelete("test1_index/test/1")
        assertEquals(401, result.getResponseCode)
      }
      "allow deleting from test2_index" in {
        val result = actionManager.actionDelete("test2_index/test/1")
        assertEquals(200, result.getResponseCode)
      }
    }
  }
}

object ActionsTests {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    val documentManager = new DocumentManagerJ(adminRestClient)
    documentManager.insertDoc("/test1_index/test/1", "{\"hello\":\"world\"}")
    documentManager.insertDoc("/test2_index/test/1", "{\"hello\":\"world\"}")
  }
}
