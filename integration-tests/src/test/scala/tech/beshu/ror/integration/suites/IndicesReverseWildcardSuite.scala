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

import org.scalatest.{Matchers, WordSpec}
import tech.beshu.ror.integration.suites.base.support.{BaseIntegrationTest, SingleClientSupport}
import tech.beshu.ror.utils.containers.generic.{ElasticsearchNodeDataInitializer, EsClusterSettings, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{DocumentManagerJ, SearchManagerJ}
import tech.beshu.ror.utils.httpclient.RestClient

trait IndicesReverseWildcardSuite
  extends WordSpec
    with BaseIntegrationTest
    with SingleClientSupport
    with Matchers {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/indices_reverse_wildcards/readonlyrest.yml"

  override lazy val targetEs = container.nodesContainers.head

  override lazy val container = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      nodeDataInitializer = IndicesReverseWildcardSuite.nodeDataInitializer()
    )
  )
  private lazy val searchManager = new SearchManagerJ(adminClient)

  "A search request" should {
    "return proper data" when {
      "direct index search is used" in {
        val response = searchManager.search("/logstash-a1/_search")

        response.getResponseCode should be(200)
        response.getSearchHits.size() should be(1)
        response.getSearchHits.get(0).get("_id") should be("doc-a1")
      }
      "simple wildcard search is used" in {
        val response = searchManager.search("/logstash-a*/_search")

        response.getResponseCode should be(200)
        response.getSearchHits.size() should be(2)
        response.getSearchHits.get(0).get("_id") should be("doc-a1")
        response.getSearchHits.get(1).get("_id") should be("doc-a2")
      }
      "reverse wildcard search is used" in {
        val response = searchManager.search("/logstash-*/_search")

        response.getResponseCode should be(200)
        response.getSearchHits.size() should be(2)
        response.getSearchHits.get(0).get("_id") should be("doc-a1")
        response.getSearchHits.get(1).get("_id") should be("doc-a2")
      }

      "reverse total wildcard search is used" in {
        val response = searchManager.search("/*/_search")

        response.getResponseCode should be(200)
        response.getSearchHits.size() should be(2)
        response.getSearchHits.get(0).get("_id") should be("doc-a1")
        response.getSearchHits.get(1).get("_id") should be("doc-a2")
      }

      "generic search all is used" in {
        val response = searchManager.search("/_search")

        response.getResponseCode should be(200)
        response.getSearchHits.size() should be(2)
        response.getSearchHits.get(0).get("_id") should be("doc-a1")
        response.getSearchHits.get(1).get("_id") should be("doc-a2")
      }
    }
  }
}

object IndicesReverseWildcardSuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    val documentManager = new DocumentManagerJ(adminRestClient)
    insertDoc("a1")
    insertDoc("a2")
    insertDoc("b1")
    insertDoc("b2")

    def insertDoc(index: String) = {
      documentManager.insertDocAndWaitForRefresh(
        s"/logstash-$index/documents/doc-$index",
        s"""{"title": "$index"}"""
      )
    }
  }
}