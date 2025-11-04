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
import tech.beshu.ror.utils.TestUjson.ujson
import tech.beshu.ror.utils.containers.ElasticsearchNodeDataInitializer
import tech.beshu.ror.utils.elasticsearch.BaseManager.SimpleHeader
import tech.beshu.ror.utils.elasticsearch.{CatManager, DocumentManager, IndexManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers

class MiscSuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport
    with ESVersionSupportForAnyWordSpecLike 
    with CustomScalaTestMatchers {

  override implicit val rorConfigFileName: String = "/misc/readonlyrest.yml"

  override def nodeDataInitializer: Option[ElasticsearchNodeDataInitializer] = Some(MiscSuite.nodeDataInitializer())

  private lazy val adminIndexManager = new IndexManager(basicAuthClient("admin", "container"), esVersionUsed)

  "An x_forwarded_for" should {
    "block the request because hostname is not resolvable" in {
      val userClusterStateManager = new CatManager(
        client = basicAuthClient("user1", "pass"),
        additionalHeaders = Map("X-Forwarded-For" -> "es-pub7"),
        esVersion = esVersionUsed
      )
      val response = userClusterStateManager.healthCheck()

      response should have statusCode 403
    }
    "allow the request when it doesn't contain x-forwarded-for header" in {
      val userClusterStateManager = new CatManager(
        client = basicAuthClient("admin", "admin123"),
        esVersion = esVersionUsed
      )
      val response = userClusterStateManager.main()

      response should have statusCode 200
    }
  }
  "Warning response header" should {
    "be exposed in ror response" excludeES(allEs6x, allEs8x, allEs9x) in {
      // headers are used only for deprecation. Deprecated features change among versions es8xx modules should use other method to test deprecation warnings
      val indexResponse = adminIndexManager.createIndex(
        index = "typed_index",
        params = Map(
          "master_timeout" -> "30s",
          "include_type_name" -> "true",
          "timeout" -> "30s",
        ))

      indexResponse should have statusCode 200
      val warningHeader = indexResponse.headers.collectFirst { case SimpleHeader("Warning", value) => value }.get
      warningHeader should include("[types removal] Using include_type_name in create index requests is deprecated. The parameter will be removed in the next major version.")
    }
  }
  "JWT auth and filter variable case" in {
    val searchManager = new SearchManager(
      tokenAuthClient(
      """Bearer eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ0ZXN0IiwidXNlcklkIjoidXNlcjUiLCJ1c2VyX2lkX2xpc3QiOlsiYWxpY2UiLCJib2IiXX0.aPtoDBPTVhtLPmwSKO6g41NEs7qhEeDG53e4aeHMQ66avoBblkUuDYBB2nFlQCxi90lfwXRzdkFYvjhtqijBP98uz6-bs8HmlfOG6_DoZRlWy5FLtdAS7F7UReqKtQ36KjNI7-YJtSTyaiDwymXPxiP44e4jJ3kJy1yx7r3ALmX7wbys1JGrUTddWQW0GWY8p2bf-hpmUmuu8AUGjfIOqYBBFWLT-NyuTYTMGUZlF8yxoBlp8twMVrqqT6ejLRQwgVxIoFL1g04uMwXUDit2dCzk5qTMAim3U-8Cgol7gi_yR-23BPY_pOejK9QPseXhpKQ9sW7v_jnLMuaI86jLhA"""
      ),
      esVersionUsed
    )
    val result = searchManager.search(
      ujson.read("""{"query": {"terms":{"user_id": ["alice", "bob"]}}}""")
    )

    result should have statusCode 200
    result.searchHits.size should be(1)
    result.searchHits(0)("_source")("user_id").str should be("alice")
  }
  "Main endpoint" should {
    "be protected" in {
      val unknownUserCatManager = new CatManager(basicAuthClient("unknown", "unknown"), esVersion = esVersionUsed)
      val response = unknownUserCatManager.main()
      response should have statusCode 403
    }
  }
}

object MiscSuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    documentManager.createDoc("index1", 1, ujson.read("""{"user_id":"ivan"}""")).force()
    documentManager.createDoc("index1", 2, ujson.read("""{"user_id":"alice"}""")).force()
  }
}