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
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.utils.containers.EsContainerCreator
import tech.beshu.ror.utils.elasticsearch.{DocumentManagerJ, IndexManager, IndexManagerJ, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient


trait ClosedIndicesSuite
  extends WordSpec
    with BaseSingleNodeEsClusterTest
    with Matchers {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/closed_indices/readonlyrest.yml"

  // we use admin client here so 'CONTAINER ADMIN' block is matched. We need to match 'Getter' block
  private lazy val searchManager = new SearchManager(adminClient, additionalHeaders = Map("x-api-key" -> "g"))
  private lazy val indexManager = new IndexManager(adminClient, additionalHeaders = Map("x-api-key" -> "g"))

  "A search request" should {
    "return only data related to a1 index and ignore closed a2 index" when {
      "direct index search is used" in {
        val response = searchManager.search("intentp1_a1")

        response.responseCode should be(200)
        response.searchHits.size should be(1)
        response.searchHits.head("_id").str should be("doc-a1")
      }
      "wildcard search is used" in {
        val response = searchManager.search("*")

        response.responseCode should be(200)
        response.searchHits.size should be(1)
        response.searchHits.head("_id").str should be("doc-a1")
      }
      "generic search all" in {
        val response = searchManager.search()

        response.responseCode should be(200)
        response.searchHits.size should be(1)
        response.searchHits.head("_id").str should be("doc-a1")
      }

      "get mappings is used" in {
        val response = indexManager.getMapping(indexName = "intentp1_*", field = "*")

        response.responseCode should be(200)
        response.body.contains("intentp1_a1") should be(true)
        response.body.contains("intentp1_a2") should be(false)
      }
    }
  }

  override val nodeDataInitializer = Some {
    (_, adminRestClient: RestClient) => {
      val documentManager = new DocumentManagerJ(adminRestClient)
      documentManager.insertDocAndWaitForRefresh(
        "/intentp1_a1/documents/doc-a1",
        """{"title": "a1"}"""
      )
      documentManager.insertDocAndWaitForRefresh(
        "/intentp1_a2/documents/doc-a2",
        """{"title": "a2"}"""
      )

      val indexManager = new IndexManagerJ(adminRestClient)
      indexManager.close("/intentp1_a2")
    }
  }

}