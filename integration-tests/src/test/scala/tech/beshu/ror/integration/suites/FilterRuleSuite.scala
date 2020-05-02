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

import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.integration.suites.base.support.{BaseIntegrationTest, SingleClientSupport}
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsClusterSettings, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{DocumentManagerJ, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.ScalaUtils.retry
import tech.beshu.ror.utils.misc.Version

trait FilterRuleSuite
  extends WordSpec
    with BaseIntegrationTest
    with SingleClientSupport {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/filter_rules/readonlyrest.yml"

  override lazy val targetEs = container.nodes.head

  override lazy val container = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      numberOfInstances = 2,
      nodeDataInitializer = FilterRuleSuite.nodeDataInitializer()
    )
  )

  "A filter rule" should {
    "show only doc according to defined filter" when {
      "search api is used" when {
        "custom query in request body is sent" in {
          retry(times = 3) {
            val searchManager = new SearchManager(basicAuthClient("user1", "pass"))
            val result = searchManager.search("/test1_index/_search", """{ "query": { "term": { "code": 1 }}}""")

            result.responseCode shouldBe 200
            result.searchHits.size shouldBe 1

            result.hit(0)("db_name").str shouldBe "db_user1"
            result.hit(0)("code").num shouldBe 1
          }
        }
        "there is no query in request body" in {
          retry(times = 3) {
            val searchManager = new SearchManager(basicAuthClient("user1", "pass"))
            val result = searchManager.search("/test1_index/_search")

            result.responseCode shouldBe 200
            result.searchHits.size shouldBe 2

            result.hit(0)("db_name").str shouldBe "db_user1"
            result.hit(0)("code").num shouldBe 1
            result.hit(1)("db_name").str shouldBe "db_user1"
            result.hit(1)("code").num shouldBe 2
          }
        }
      }
      "msearch api is used" in {
        retry(times = 3) {
          val searchManager = new SearchManager(basicAuthClient("user1", "pass"))
          val matchAllIndicesQuery = Seq(
            """{"index":"*"}""",
            """{"query" : {"match_all" : {}}}""")
          val result = searchManager.mSearchUnsafe(matchAllIndicesQuery: _*)

          result.responseCode shouldBe 200
          result.responses.size shouldBe 1
          result.searchHitsForResponse(0).size shouldBe 2
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
    documentManager.insertDocAndWaitForRefresh(s"/$index/${`type`}/1", s"""{"db_name":"db_user1", "code": 1}""")
    documentManager.insertDocAndWaitForRefresh(s"/$index/${`type`}/2", s"""{"db_name":"db_user1", "code": 2}""")
    documentManager.insertDocAndWaitForRefresh(s"/$index/${`type`}/3", s"""{"db_name":"db_user2", "code": 1}""")
    documentManager.insertDocAndWaitForRefresh(s"/$index/${`type`}/4", s"""{"db_name":"db_user3", "code": 2}""")
  }
}
