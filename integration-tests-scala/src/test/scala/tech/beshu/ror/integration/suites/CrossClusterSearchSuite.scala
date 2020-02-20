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
import com.dimafeng.testcontainers.ForAllTestContainer
import org.junit.Assert.assertEquals
import org.scalatest.WordSpec
import tech.beshu.ror.integration.utils.ESVersionSupport
import tech.beshu.ror.utils.containers.generic._
import tech.beshu.ror.utils.elasticsearch.{DocumentManagerJ, SearchManagerJ}
import tech.beshu.ror.utils.httpclient.RestClient

trait CrossClusterSearchSuite
  extends WordSpec
    with ForAllTestContainer
    with EsClusterProvider
    with ClientProvider
    with TargetEsContainer
    with ESVersionSupport {
  this: EsContainerCreator =>

  val rorConfigFileName = "/cross_cluster_search/readonlyrest.yml"
  override lazy val container = createRemoteClustersContainer(
    NonEmptyList.of(
      EsClusterSettings(name = "ROR1", rorConfigFileName = rorConfigFileName, nodeDataInitializer = CrossClusterSearchSuite.nodeDataInitializer()),
      EsClusterSettings(name = "ROR2", rorConfigFileName = rorConfigFileName, nodeDataInitializer = CrossClusterSearchSuite.nodeDataInitializer()),
    ),
    CrossClusterSearchSuite.remoteClustersInitializer()
  )
  override val targetEsContainer = container.localClusters.head.nodesContainers.head

  private lazy val user1SearchManager = new SearchManagerJ(client("dev1", "test"))
  private lazy val user2SearchManager = new SearchManagerJ(client("dev2", "test"))

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

object CrossClusterSearchSuite {

  def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    val documentManager = new DocumentManagerJ(adminRestClient)
    documentManager.insertDoc("/test1_index/test/1", "{\"hello\":\"world\"}")
    documentManager.insertDoc("/test1_index/test/2", "{\"hello\":\"ROR\"}")
    documentManager.insertDoc("/test2_index/test/1", "{\"hello\":\"world\"}")
    documentManager.insertDoc("/test2_index/test/2", "{\"hello\":\"ROR\"}")
  }

  def remoteClustersInitializer(): RemoteClustersInitializer =
    (localClusterRepresentatives: NonEmptyList[EsContainer]) => {
      Map("odd" -> localClusterRepresentatives)
    }

}
