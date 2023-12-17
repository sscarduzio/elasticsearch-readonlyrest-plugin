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
import tech.beshu.ror.utils.containers.ElasticsearchNodeDataInitializer
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers

class IndicesReverseWildcardSuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport
    with ESVersionSupportForAnyWordSpecLike
    with CustomScalaTestMatchers {

  override implicit val rorConfigFileName: String = "/indices_reverse_wildcards/readonlyrest.yml"

  override def nodeDataInitializer = Some(IndicesReverseWildcardSuite.nodeDataInitializer())

  private lazy val dev1SearchManager = new SearchManager(basicAuthClient("dev1", "test"), esVersionUsed)

  "A search request" should {
    "return proper data" when {
      "direct index search is used" in {
        val response = dev1SearchManager.search("logstash-a1")

        response should have statusCode 200
        response.searchHits.size should be(1)
        response.searchHits.head("_id").str should be("doc-a1")
      }
      "simple wildcard search is used" in {
        val response = dev1SearchManager.search("logstash-a*")

        response should have statusCode 200
        response.searchHits.size should be(2)
        response.searchHits.map(_("_id").str) should contain allOf("doc-a1", "doc-a2")
      }
      "reverse wildcard search is used" in {
        val response = dev1SearchManager.search("logstash-*")

        response should have statusCode 200
        response.searchHits.size should be(2)
        response.searchHits.map(_("_id").str) should contain allOf("doc-a1", "doc-a2")
      }

      "reverse total wildcard search is used" in {
        val response = dev1SearchManager.search("*")

        response should have statusCode 200
        response.searchHits.size should be(2)
        response.searchHits.map(_("_id").str) should contain allOf("doc-a1", "doc-a2")
      }

      "generic search all is used" in {
        val response = dev1SearchManager.search()

        response should have statusCode 200
        response.searchHits.size should be(2)
        response.searchHits.map(_("_id").str) should contain allOf("doc-a1", "doc-a2")
      }
    }
  }
}

object IndicesReverseWildcardSuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    documentManager.createDoc("logstash-a1", "doc-a1", ujson.read(s"""{"title": "logstash-a1"}"""))
    documentManager.createDoc("logstash-a2", "doc-a2", ujson.read(s"""{"title": "logstash-a2"}"""))
    documentManager.createDoc("logstash-b1", "doc-b1", ujson.read(s"""{"title": "logstash-b1"}"""))
    documentManager.createDoc("logstash-b2", "doc-b2", ujson.read(s"""{"title": "logstash-b2"}"""))
  }
}