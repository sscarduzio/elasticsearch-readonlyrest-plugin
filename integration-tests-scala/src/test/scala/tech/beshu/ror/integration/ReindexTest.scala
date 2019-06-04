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
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, ReadonlyRestEsCluster, ReadonlyRestEsClusterContainer}
import tech.beshu.ror.utils.elasticsearch.{ActionManager, DocumentManager}

class ReindexTest extends WordSpec with ForAllTestContainer {

  override val container: ReadonlyRestEsClusterContainer = ReadonlyRestEsCluster.createLocalClusterContainer(
    rorConfigFileName = "/reindex/readonlyrest.yml",
    numberOfInstances = 1,
    ReindexTest.nodeDataInitializer()
  )

  private lazy val user1ActionManager = new ActionManager(container.nodesContainers.head.client("dev1", "test"))

  "A reindex request" should {
    "be able to proceed" when {
      "user has permission to source index" in {
        val result = user1ActionManager.action("_reindex", ReindexTest.reindexPayload("test1_index"))
        assertEquals(200, result.getResponseCode)
      }
    }
    "not be able to proceed" when {
      "user has no permission to source index" in {
        val result = user1ActionManager.action("_reindex", ReindexTest.reindexPayload("test2_index"))
        assertEquals(401, result.getResponseCode)
      }
    }
  }
}


object ReindexTest {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (documentManager: DocumentManager) => {
    documentManager.insertDoc("/test1_index/test/1", "{\"hello\":\"world\"}")
    documentManager.insertDoc("/test2_index/test/1", "{\"hello\":\"world\"}")
  }

  private def reindexPayload(indexName: String) = {
    s"""{"source":{"index":"$indexName"},"dest":{"index":"${indexName}_reindexed"}}"""
  }
}
