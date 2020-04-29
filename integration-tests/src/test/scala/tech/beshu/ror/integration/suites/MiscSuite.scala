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

import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.integration.suites.base.support.{BaseIntegrationTest, SingleClientSupport}
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsClusterSettings, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{ClusterManager, DocumentManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait MiscSuite
  extends WordSpec
    with BaseIntegrationTest
    with SingleClientSupport {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/misc/readonlyrest.yml"

  override lazy val targetEs = container.nodes.head

  override lazy val container = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      numberOfInstances = 2,
      nodeDataInitializer = MiscSuite.nodeDataInitializer()
    )
  )

  private lazy val userClusterStateManager = new ClusterManager(
    client = basicAuthClient("user1", "pass"),
    additionalHeaders = Map("X-Forwarded-For" -> "es-pub7"),
    esVersion = targetEs.esVersion)

  "An x_forwarded_for" should {
    "block the request because hostname is not resolvable" in {
      val response = userClusterStateManager.healthCheck()

      response.responseCode should be(401)
    }
  }

  "JWT auth and filter variable case" in {
    val searchManager = new SearchManager(tokenAuthClient(
      """Bearer eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ0ZXN0IiwidXNlcklkIjoidXNlcjUiLCJ1c2VyX2lkX2xpc3QiOlsiYWxpY2UiLCJib2IiXX0.aPtoDBPTVhtLPmwSKO6g41NEs7qhEeDG53e4aeHMQ66avoBblkUuDYBB2nFlQCxi90lfwXRzdkFYvjhtqijBP98uz6-bs8HmlfOG6_DoZRlWy5FLtdAS7F7UReqKtQ36KjNI7-YJtSTyaiDwymXPxiP44e4jJ3kJy1yx7r3ALmX7wbys1JGrUTddWQW0GWY8p2bf-hpmUmuu8AUGjfIOqYBBFWLT-NyuTYTMGUZlF8yxoBlp8twMVrqqT6ejLRQwgVxIoFL1g04uMwXUDit2dCzk5qTMAim3U-8Cgol7gi_yR-23BPY_pOejK9QPseXhpKQ9sW7v_jnLMuaI86jLhA"""
    ))
    val result = searchManager.search(
      "_search",
      """{"query": {"terms":{"user_id": ["alice", "bob"]}}}"""
    )

    result.responseCode should be (200)
    result.searchHits.size should be (1)
    result.searchHits(0)("_source")("user_id").str should be ("alice")
  }
}

object MiscSuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    documentManager.createDoc("index1", 1, ujson.read("""{"user_id":"ivan"}"""))
    documentManager.createDoc("index1", 2, ujson.read("""{"user_id":"alice"}"""))
  }
}