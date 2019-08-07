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

import java.util.{Map => JMap}

import com.dimafeng.testcontainers.ForAllTestContainer
import org.junit.Assert.assertEquals
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, ReadonlyRestEsCluster, ReadonlyRestEsClusterContainer}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.ScalaUtils.retry

class FilterRuleTests extends WordSpec with ForAllTestContainer {

  override val container: ReadonlyRestEsClusterContainer = ReadonlyRestEsCluster.createLocalClusterContainer(
    name = "ROR1",
    rorConfigFileName = "/filter_rules/readonlyrest.yml",
    numberOfInstances = 2,
    FilterRuleTests.nodeDataInitializer()
  )

  private lazy val searchManager = new SearchManager(container.nodesContainers.head.client("user1", "pass"))

  "A filter rule" should {
    "show only doc according to defined filter" when {
      "search api is used" in {
        retry(times = 3) {
          val result = searchManager.search("/test1_index/_search")
          assertEquals(200, result.getResponseCode)
          result.getResults.size() should be(1)
          result.getResults.get(0).get("_source").asInstanceOf[JMap[String, String]].get("db_name") should be("db_user1")
        }
      }
      "msearch api is used" in {
        retry(times = 3) {
          val matchAllIndicesQuery = "{\"index\":\"*\"}\n" + "{\"query\" : {\"match_all\" : {}}}\n"
          val result = searchManager.mSearch(matchAllIndicesQuery)
          assertEquals(200, result.getResponseCode)
          result.getResults.size() should be(1)
          result.getResults.get(0).get("_source").asInstanceOf[JMap[String, String]].get("db_name") should be("db_user1")
        }
      }
    }
  }
}

object FilterRuleTests {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient)
    documentManager.insertDocAndWaitForRefresh("/test1_index/test/1", s"""{"db_name":"db_user1"}""")
    documentManager.insertDocAndWaitForRefresh("/test1_index/test/2", s"""{"db_name":"db_user2"}""")
    documentManager.insertDocAndWaitForRefresh("/test1_index/test/3", s"""{"db_name":"db_user3"}""")
  }
}
