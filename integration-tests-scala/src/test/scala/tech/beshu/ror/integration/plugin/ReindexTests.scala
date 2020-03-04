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

import com.dimafeng.testcontainers.ForAllTestContainer
import org.junit.Assert.assertEquals
import org.scalatest.WordSpec
import tech.beshu.ror.integration.utils.ESVersionSupport
import tech.beshu.ror.utils.containers.ReadonlyRestEsCluster.AdditionalClusterSettings
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, ReadonlyRestEsCluster, ReadonlyRestEsClusterContainer}
import tech.beshu.ror.utils.elasticsearch.{ActionManagerJ, DocumentManagerJ}
import tech.beshu.ror.utils.httpclient.RestClient

class ReindexTests extends WordSpec with ForAllTestContainer with ESVersionSupport {

  override lazy val container: ReadonlyRestEsClusterContainer = ReadonlyRestEsCluster.createLocalClusterContainer(
    name = "ROR1",
    rorConfigFileName = "/reindex/readonlyrest.yml",
    clusterSettings = AdditionalClusterSettings(nodeDataInitializer = ReindexTests.nodeDataInitializer())
  )

  private lazy val user1ActionManager = new ActionManagerJ(container.nodesContainers.head.client("dev1", "test"))

  "A reindex request" should {
    "be able to proceed" when {
      "user has permission to source index and dest index" excludeES("es51x", "es52x") in {
        val result = user1ActionManager.actionPost("_reindex", ReindexTests.reindexPayload("test1_index"))
        assertEquals(200, result.getResponseCode)
      }
    }
    "not be able to proceed" when {
      "user has no permission to source index and dest index" excludeES("es51x", "es52x") in {
        val result = user1ActionManager.actionPost("_reindex", ReindexTests.reindexPayload("test2_index"))
        assertEquals(401, result.getResponseCode)
      }
    }
  }
}

object ReindexTests {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    val documentManager = new DocumentManagerJ(adminRestClient)
    documentManager.insertDoc("/test1_index/test/1", "{\"hello\":\"world\"}")
    documentManager.insertDoc("/test2_index/test/1", "{\"hello\":\"world\"}")
  }

  private def reindexPayload(indexName: String) = {
    s"""{"source":{"index":"$indexName"},"dest":{"index":"${indexName}_reindexed"}}"""
  }
}
