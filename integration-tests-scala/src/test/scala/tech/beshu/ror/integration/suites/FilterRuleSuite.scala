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

import java.util.{Map => JMap}

import com.dimafeng.testcontainers.ForAllTestContainer
import org.junit.Assert.assertEquals
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.utils.containers.generic._
import tech.beshu.ror.utils.containers.generic.providers.{RorConfigFileNameProvider, SingleClient, SingleEsTarget}
import tech.beshu.ror.utils.elasticsearch.{DocumentManagerJ, SearchManagerJ}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.ScalaUtils.retry
import tech.beshu.ror.utils.misc.Version

trait FilterRuleSuite
  extends WordSpec
    with ForAllTestContainer
    with EsClusterProvider
    with SingleClient
    with SingleEsTarget
    with RorConfigFileNameProvider {
  this: EsContainerCreator =>

  override val rorConfigFileName = "/current_user_metadata/readonlyrest.yml"

  override lazy val targetEs = container.nodesContainers.head

  override lazy val container = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      rorConfigFileName = rorConfigFileName,
      numberOfInstances = 2,
      nodeDataInitializer = FilterRuleSuite.nodeDataInitializer()
    )
  )

  private lazy val searchManager = new SearchManagerJ(client("user1", "pass"))

  "A filter rule" should {
    "show only doc according to defined filter" when {
      "search api is used" in {
        retry(times = 3) {
          val result = searchManager.search("/test1_index/_search")
          assertEquals(200, result.getResponseCode)
          result.getSearchHits.size() should be(1)
          result.getSearchHits.get(0).get("_source").asInstanceOf[JMap[String, String]].get("db_name") should be("db_user1")
        }
      }
      "msearch api is used" in {
        retry(times = 3) {
          val matchAllIndicesQuery = "{\"index\":\"*\"}\n" + "{\"query\" : {\"match_all\" : {}}}\n"
          val result = searchManager.mSearch(matchAllIndicesQuery)
          assertEquals(200, result.getResponseCode)
          result.getMSearchHits.size() should be(1)
          result.getMSearchHits.get(0).get("_source").asInstanceOf[JMap[String, String]].get("db_name") should be("db_user1")
        }
      }
    }
  }
}

object FilterRuleSuite {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    if (Version.greaterOrEqualThan(esVersion, 7, 0, 0)) {
      add3Docs(adminRestClient, "test1_index", "_doc")
    } else {
      add3Docs(adminRestClient, "test1_index", "doc")
    }
  }

  private def add3Docs(adminRestClient: RestClient, index: String, `type`: String): Unit = {
    val documentManager = new DocumentManagerJ(adminRestClient)
    documentManager.insertDocAndWaitForRefresh(s"/$index/${`type`}/1", s"""{"db_name":"db_user1"}""")
    documentManager.insertDocAndWaitForRefresh(s"/$index/${`type`}/2", s"""{"db_name":"db_user2"}""")
    documentManager.insertDocAndWaitForRefresh(s"/$index/${`type`}/3", s"""{"db_name":"db_user3"}""")
  }
}
