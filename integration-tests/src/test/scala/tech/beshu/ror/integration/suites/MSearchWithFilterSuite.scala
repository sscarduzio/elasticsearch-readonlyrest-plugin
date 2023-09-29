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

import net.jodah.failsafe.{Failsafe, RetryPolicy}
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyWordSpecLike, SingletonPluginTestSupport}
import tech.beshu.ror.utils.elasticsearch.SearchManager.MSearchResult
import tech.beshu.ror.utils.elasticsearch.{ElasticsearchTweetsInitializer, SearchManager}
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers

import java.time.Duration
import java.util.function.BiPredicate

//TODO: change test names. Current names are copies from old java integration tests
class MSearchWithFilterSuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport
    with ESVersionSupportForAnyWordSpecLike
    with CustomScalaTestMatchers {

  override implicit val rorConfigFileName: String = "/msearch_with_filter/readonlyrest.yml"

  override def nodeDataInitializer = Some(ElasticsearchTweetsInitializer)

  private val matchAllIndicesQuery = Seq(
    """{"index":"*"}""",
    """{"query" : {"match_all" : {}}}"""
  )

  private lazy val adminSearchManager = new SearchManager(adminClient)
  private lazy val user1SearchManager = new SearchManager(basicAuthClient("test1", "dev"))
  private lazy val user2SearchManager = new SearchManager(basicAuthClient("test2", "dev"))

  "userShouldOnlySeeFacebookPostsFilterTest" in {
    waitUntilAllIndexed()

    val searchResult1 = user1SearchManager.mSearchUnsafe(matchAllIndicesQuery: _*)
    val searchResult2 = user1SearchManager.mSearchUnsafe(matchAllIndicesQuery: _*)
    val searchResult3 = user2SearchManager.mSearchUnsafe(matchAllIndicesQuery: _*)
    val searchResult4 = user2SearchManager.mSearchUnsafe(matchAllIndicesQuery: _*)
    val searchResult5 = user1SearchManager.mSearchUnsafe(matchAllIndicesQuery: _*)

    assertSearchResult("facebook", searchResult1)
    assertSearchResult("facebook", searchResult2)
    assertSearchResult("twitter", searchResult3)
    assertSearchResult("twitter", searchResult4)
    assertSearchResult("facebook", searchResult5)
  }

  private def assertSearchResult(expectedIndex: String, searchResult: MSearchResult): Unit = {
    searchResult should have statusCode 200
    searchResult.responses.size shouldBe 1

    val searchHits = searchResult.searchHitsForResponse(responseIdx = 0)
    searchHits.size shouldBe 2
    searchHits.foreach { hit =>
      hit("_index").str shouldBe expectedIndex
    }
  }

  private def waitUntilAllIndexed(): Unit = {
    val retryPolicy: RetryPolicy[MSearchResult] = new RetryPolicy[MSearchResult]()
      .handleIf(resultsContainsLessElementsThan())
      .withMaxRetries(20)
      .withDelay(Duration.ofMillis(500))
      .withMaxDuration(Duration.ofSeconds(10))
    Failsafe
      .`with`[MSearchResult, RetryPolicy[MSearchResult]](retryPolicy)
      .get(() => adminSearchManager.mSearchUnsafe(matchAllIndicesQuery: _*))
  }

  private def resultsContainsLessElementsThan(): BiPredicate[MSearchResult, Throwable] =
    (searchResult: MSearchResult, throwable: Throwable) =>
      throwable != null || searchResult == null || searchResult.totalHitsForResponse(0) < 4
}
