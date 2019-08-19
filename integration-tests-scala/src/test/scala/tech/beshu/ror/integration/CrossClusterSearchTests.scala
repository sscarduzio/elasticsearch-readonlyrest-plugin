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

import cats.data.NonEmptyList
import com.dimafeng.testcontainers.ForAllTestContainer
import org.junit.Assert.assertEquals
import org.scalatest.WordSpec
import tech.beshu.ror.integration.utils.ESVersionSupport
import tech.beshu.ror.utils.containers._
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient

class CrossClusterSearchTests extends WordSpec with ForAllTestContainer with ESVersionSupport {

  override val container: ReadonlyRestEsRemoteClustersContainer = ReadonlyRestEsCluster.createRemoteClustersContainer(
    NonEmptyList.of(
      LocalClusterDef("ROR1", rorConfigFileName = "/cross_cluster_search/readonlyrest.yml", CrossClusterSearchTests.nodeDataInitializer()),
      LocalClusterDef("ROR2", rorConfigFileName = "/cross_cluster_search/readonlyrest.yml", CrossClusterSearchTests.nodeDataInitializer())
    ),
    CrossClusterSearchTests.remoteClustersInitializer()
  )

  private lazy val user1SearchManager = new SearchManager(container.localClusters.head.nodesContainers.head.client("dev1", "test"))
  private lazy val user2SearchManager = new SearchManager(container.localClusters.head.nodesContainers.head.client("dev2", "test"))

  "A cluster search for given index" should {
    "return 200 and allow user to its content" when {
      "user has permission to do so" excludeES("es51x", "es52x") in {
        val result = user1SearchManager.search("/odd:test1_index/_search")
        assertEquals(200, result.getResponseCode)
        assertEquals(2, result.getSearchHits.size)
      }
    }
    "return 401" when {
      "user has no permission to do so" excludeES("es51x", "es52x") in {
        val result = user2SearchManager.search("/odd:test1_index/_search")
        assertEquals(401, result.getResponseCode)
      }
    }
  }
}

object CrossClusterSearchTests {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient)
    documentManager.insertDoc("/test1_index/test/1", "{\"hello\":\"world\"}")
    documentManager.insertDoc("/test1_index/test/2", "{\"hello\":\"ROR\"}")
    documentManager.insertDoc("/test2_index/test/1", "{\"hello\":\"world\"}")
    documentManager.insertDoc("/test2_index/test/2", "{\"hello\":\"ROR\"}")
  }

  private def remoteClustersInitializer(): RemoteClustersInitializer =
    (localClusterRepresentatives: NonEmptyList[ReadonlyRestEsContainer]) => {
      Map("odd" -> localClusterRepresentatives)
    }

}
