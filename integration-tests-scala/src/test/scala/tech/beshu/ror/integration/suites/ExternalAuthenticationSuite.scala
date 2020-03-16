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
import tech.beshu.ror.integration.suites.base.support.{BaseIntegrationTest, SingleClientSupport}
import tech.beshu.ror.utils.containers.WireMockContainer
import tech.beshu.ror.utils.containers.generic._
import tech.beshu.ror.utils.elasticsearch.{ElasticsearchTweetsInitializer, SearchManagerJ}
import tech.beshu.ror.utils.httpclient.RestClient

import scala.collection.JavaConverters._

trait ExternalAuthenticationSuite
  extends WordSpec
    with BaseIntegrationTest
    with SingleClientSupport
    with Matchers {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/external_authentication/readonlyrest.yml"

  override lazy val targetEs = container.nodesContainers.head

  override lazy val container = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      dependentServicesContainers = List(
        DependencyDef(name = "EXT1", containerCreator = Coeval(new WireMockS(WireMockContainer.create("/external_authentication/wiremock_service1_cartman-s.json", "/external_authentication/wiremock_service1_morgan-s.json")))),
        DependencyDef(name = "EXT2", containerCreator = Coeval(new WireMockS(WireMockContainer.create("/external_authentication/wiremock_service2_cartman-s.json"))))
      ),
      nodeDataInitializer = ExternalAuthenticationSuite.nodeDataInitializer()
    )
  )

  "A search request" should {
    "return only data related to a1 index and ignore closed a2 index" when {
      "direct index search is used" in {
        val searchManager = new SearchManagerJ(
          adminClient,
          Map("x-api-key" -> "g").asJava
        )
        val response = searchManager.search("/intentp1_a1/_search")

        response.getResponseCode should be(200)
        response.getSearchHits.size() should be(1)
        response.getSearchHits.get(0).get("_id") should be("doc-a1")
      }
      "wildcard search is used" in {
        val searchManager = new SearchManagerJ(
          adminClient,
          Map("x-api-key" -> "g").asJava
        )
        val response = searchManager.search("/*/_search")

        response.getResponseCode should be(200)
        response.getSearchHits.size() should be(1)
        response.getSearchHits.get(0).get("_id") should be("doc-a1")
      }
      "generic search all" in {
        val searchManager = new SearchManagerJ(
          adminClient,
          Map("x-api-key" -> "g").asJava
        )
        val response = searchManager.search("/_search")

        response.getResponseCode should be(200)
        response.getSearchHits.size() should be(1)
        response.getSearchHits.get(0).get("_id") should be("doc-a1")
      }

      "get mappings is used" in {
        val searchManager = new SearchManagerJ(
          adminClient,
          Map("x-api-key" -> "g").asJava
        )
        val response = searchManager.search("/intentp1_*/_mapping/field/*")
        response.getResponseCode should be(200)
        response.getRawBody.contains("intentp1_a1") should be(true)
        response.getRawBody.contains("intentp1_a2") should be(false)
      }
    }
  }
}

object ExternalAuthenticationSuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    new ElasticsearchTweetsInitializer().initialize(adminRestClient)
  }
}


