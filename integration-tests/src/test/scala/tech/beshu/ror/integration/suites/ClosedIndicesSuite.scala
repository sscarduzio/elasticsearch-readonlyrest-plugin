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
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.ESVersionSupportForAnyWordSpecLike
import tech.beshu.ror.utils.containers.EsContainerCreator
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, IndexManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait ClosedIndicesSuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with ESVersionSupportForAnyWordSpecLike
    with Matchers {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/closed_indices/readonlyrest.yml"

  override val nodeDataInitializer = Some {
    (esVersion, adminRestClient: RestClient) => {
      val indexManager = new IndexManager(adminRestClient, esVersionUsed)
      val documentManager = new DocumentManager(adminRestClient, esVersion)

      documentManager
        .createDoc("intentp1_a1", "documents", "doc-a1", ujson.read("""{"title": "a1"}"""))
        .force()
      documentManager
        .createDoc("intentp1_a2", "documents", "doc-a2", ujson.read("""{"title": "a2"}"""))
        .force()

      indexManager.closeIndex("intentp1_a2").force()
    }
  }

  // we use admin client here so 'CONTAINER ADMIN' block is matched. We need to match 'Getter' block
  private lazy val searchManager = new SearchManager(rorAdminClient, additionalHeaders = Map("x-api-key" -> "g"))
  private lazy val indexManager = new IndexManager(rorAdminClient, esVersionUsed, additionalHeaders = Map("x-api-key" -> "g"))

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
}