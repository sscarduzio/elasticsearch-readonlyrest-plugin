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

import monix.eval.Coeval
import org.scalatest.{Matchers, WordSpec}
import tech.beshu.ror.integration.suites.DuplicatedResponseHeadersIssueSuite.SearchResult
import tech.beshu.ror.integration.suites.base.support.{BaseIntegrationTest, SingleClientSupport}
import tech.beshu.ror.utils.containers.WireMockContainer
import tech.beshu.ror.utils.containers.generic._
import tech.beshu.ror.utils.elasticsearch.BaseManager.SimpleHeader
import tech.beshu.ror.utils.elasticsearch.{ElasticsearchTweetsInitializer, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait DuplicatedResponseHeadersIssueSuite
  extends WordSpec
    with BaseIntegrationTest
    with SingleClientSupport
    with Matchers {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/duplicated_response_headers_issue/readonlyrest.yml"

  override lazy val targetEs = container.nodesContainers.head

  override lazy val container = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      dependentServicesContainers = List(
        DependencyDef(name = "EXT1", containerCreator = Coeval(new WireMockS(WireMockContainer.create(
          "/duplicated_response_headers_issue/auth-s.json",
          "/duplicated_response_headers_issue/brian_groups-s.json",
          "/duplicated_response_headers_issue/freddie_groups-s.json"))))
      ),
      nodeDataInitializer = DuplicatedResponseHeadersIssueSuite.nodeDataInitializer()
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

    f2 should be(f1)
    f3 should be(f1)
    b2 should be(b1)
    b3 should be(b1)
    b4 should be(b1)
    b5 should be(b1)
    b6 should be(b1)
    b7 should be(b1)
    b8 should be(b1)
  }

  private def searchCall(searchManager: SearchManager) = {
    val result = searchManager.search("/neg*/_search")
    result.responseCode should be(200)
    SearchResult(result.responseCode, result.headers)
  }
}

object DuplicatedResponseHeadersIssueSuite {

  final case class SearchResult(responseCode: Int, headers: List[SimpleHeader])
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    new ElasticsearchTweetsInitializer().initialize(adminRestClient)
  }
}
