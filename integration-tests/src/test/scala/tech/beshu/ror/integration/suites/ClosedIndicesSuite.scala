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

import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyWordSpecLike, SingletonPluginTestSupport}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, IndexManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers

class ClosedIndicesSuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport
    with ESVersionSupportForAnyWordSpecLike
    with CustomScalaTestMatchers {

  override implicit val rorConfigFileName: String = "/closed_indices/readonlyrest.yml"

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

  private lazy val dev1SearchManager = new SearchManager(basicAuthClient("dev1", "test"), esVersionUsed)
  private lazy val dev1IndexManager = new IndexManager(basicAuthClient("dev1", "test"), esVersionUsed)

  "A search request" should {
    "return only data related to a1 index and ignore closed a2 index" when {
      "direct index search is used" in {
        val response = dev1SearchManager.search("intentp1_a1")

        response should have statusCode 200
        val foundIndices = response.searchHits.map(_("_index").str)
        foundIndices should contain("intentp1_a1")
        foundIndices should not contain ("intentp1_a2")
      }
      "wildcard search is used" in {
        val response = dev1SearchManager.search("*")

        response should have statusCode 200
        val foundIndices = response.searchHits.map(_("_index").str)
        foundIndices should contain("intentp1_a1")
        foundIndices should not contain ("intentp1_a2")
      }
      "generic search all" in {
        val response = dev1SearchManager.search()

        response should have statusCode 200
        val foundIndices = response.searchHits.map(_("_index").str)
        foundIndices should contain("intentp1_a1")
        foundIndices should not contain ("intentp1_a2")
      }
      "get mappings is used" in {
        val response = dev1IndexManager.getMapping(indexName = "intentp1_*", field = "*")

        response should have statusCode 200
        response.body.contains("intentp1_a1") should be(true)
        response.body.contains("intentp1_a2") should be(false)
      }
    }
  }
}