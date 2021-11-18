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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.DuplicatedResponseHeadersIssueSuite.SearchResult
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.integration.utils.ESVersionSupportForAnyWordSpecLike
import tech.beshu.ror.utils.containers.dependencies.wiremock
import tech.beshu.ror.utils.containers.{EsClusterContainer, EsClusterSettings, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.BaseManager.SimpleHeader
import tech.beshu.ror.utils.elasticsearch.{ElasticsearchTweetsInitializer, SearchManager}

//TODO change test names. Current names are copies from old java integration tests
trait DuplicatedResponseHeadersIssueSuite
  extends AnyWordSpec
    with BaseEsClusterIntegrationTest
    with ESVersionSupportForAnyWordSpecLike
    with SingleClientSupport
    with Matchers {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/duplicated_response_headers_issue/readonlyrest.yml"

  override lazy val targetEs = container.nodes.head

  override lazy val clusterContainer: EsClusterContainer = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      dependentServicesContainers = List(
        wiremock(name = "EXT1", mappings =
          "/duplicated_response_headers_issue/auth.json",
          "/duplicated_response_headers_issue/brian_groups.json",
          "/duplicated_response_headers_issue/freddie_groups.json")
      ),
      nodeDataInitializer = ElasticsearchTweetsInitializer,
      xPackSupport = false,
    )
  )

  "everySearchCallForEachUserShouldReturnTheSameResult" in {
    val freddieSearchManager = new SearchManager(basicAuthClient("freddie", "freddie"))
    val brianSearchManager = new SearchManager(basicAuthClient("brian", "brian"))

    val b1 = searchCall(brianSearchManager)
    val f1 = searchCall(freddieSearchManager)
    val f2 = searchCall(freddieSearchManager)
    val b2 = searchCall(brianSearchManager)
    val b3 = searchCall(brianSearchManager)
    val f3 = searchCall(freddieSearchManager)
    val b4 = searchCall(brianSearchManager)
    val b5 = searchCall(brianSearchManager)
    val b6 = searchCall(brianSearchManager)
    val b7 = searchCall(brianSearchManager)
    val b8 = searchCall(brianSearchManager)

    f2 shouldBe f1
    f3 shouldBe f1
    b2 shouldBe b1
    b3 shouldBe b1
    b4 shouldBe b1
    b5 shouldBe b1
    b6 shouldBe b1
    b7 shouldBe b1
    b8 shouldBe b1
  }

  private def searchCall(searchManager: SearchManager) = {
    val result = searchManager.search("neg*")
    result.responseCode shouldBe 200
    SearchResult(result.responseCode, result.headers)
  }
}

object DuplicatedResponseHeadersIssueSuite {

  final case class SearchResult(responseCode: Int, headers: Set[SimpleHeader])
}
