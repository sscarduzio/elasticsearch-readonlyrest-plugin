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

import org.junit.Assert.assertEquals
import org.scalatest.{Matchers, WordSpec}
import tech.beshu.ror.integration.suites.base.support.{BaseIntegrationTest, SingleClientSupport}
import tech.beshu.ror.utils.containers.generic.{ElasticsearchNodeDataInitializer, EsClusterSettings, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.SearchManagerJ.MSearchResult
import tech.beshu.ror.utils.elasticsearch.{ElasticsearchTweetsInitializer, SearchManagerJ}
import tech.beshu.ror.utils.httpclient.RestClient

//TODO change test names. Current names are copies from old java integration tests
trait MSearchWithFilterSuite
  extends WordSpec
    with BaseIntegrationTest
    with SingleClientSupport
    with Matchers {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/msearch_with_filter/readonlyrest.yml"

  override lazy val targetEs = container.nodesContainers.head

  override lazy val container = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      nodeDataInitializer = MSearchWithFilterSuite.nodeDataInitializer()
    )
  )

  private val matchAllIndicesQuery =
    """{"index":"*"}
      |{"query" : {"match_all" : {}}}
      |""".stripMargin

  private lazy val user1SearchManager = new SearchManagerJ(basicAuthClient("test1", "dev"))
  private lazy val user2SearchManager = new SearchManagerJ(basicAuthClient("test2", "dev"))

  "userShouldOnlySeeFacebookPostsFilterTest" in {
    val searchResult1 = user1SearchManager.mSearch(matchAllIndicesQuery)
    val searchResult2 = user1SearchManager.mSearch(matchAllIndicesQuery)
    val searchResult3 = user2SearchManager.mSearch(matchAllIndicesQuery)
    val searchResult4 = user2SearchManager.mSearch(matchAllIndicesQuery)
    val searchResult5 = user1SearchManager.mSearch(matchAllIndicesQuery)

    assertSearchResult("facebook", searchResult1)
    assertSearchResult("facebook", searchResult2)
    assertSearchResult("twitter", searchResult3)
    assertSearchResult("twitter", searchResult4)
    assertSearchResult("facebook", searchResult5)
  }

  private def assertSearchResult(expectedIndex: String, searchResult: MSearchResult): Unit = {
    searchResult.getResponseCode shouldBe 200
    searchResult.getMSearchHits.size shouldBe 2
    searchResult.getMSearchHits.forEach(result => assertEquals(expectedIndex, result.get("_index")))
  }
}

object MSearchWithFilterSuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    new ElasticsearchTweetsInitializer().initialize(adminRestClient)
  }
}
