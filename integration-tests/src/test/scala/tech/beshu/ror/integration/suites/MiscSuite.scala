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
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.integration.utils.ESVersionSupport
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsClusterContainer, EsClusterSettings, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.BaseManager.SimpleHeader
import tech.beshu.ror.utils.elasticsearch.{CatManager, ClusterManager, DocumentManager, IndexManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait MiscSuite
  extends WordSpec
    with BaseEsClusterIntegrationTest
    with SingleClientSupport
    with ESVersionSupport {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/misc/readonlyrest.yml"

  override lazy val targetEs = container.nodes.head

  override lazy val clusterContainer: EsClusterContainer = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      numberOfInstances = 2,
      nodeDataInitializer = MiscSuite.nodeDataInitializer(),
      xPackSupport = isUsingXpackSupport,
    )
  )

  private lazy val userClusterStateManager = new CatManager(
    client = basicAuthClient("user1", "pass"),
    additionalHeaders = Map("X-Forwarded-For" -> "es-pub7"),
    esVersion = targetEs.esVersion)
  private lazy val dev1IndexManager = new IndexManager(basicAuthClient("admin", "container"))

//  "An x_forwarded_for" should {
//    "block the request because hostname is not resolvable" in {
//      val response = userClusterStateManager.healthCheck()
//
//      response.responseCode should be(401)
//    }
//  }
//  "Warning response header" should {
//    "be exposed in ror response" excludeES(allEs5x, allEs6x, rorProxy) in {
//      // headers are used only for deprecation. Deprecated features change among versions es8xx modules should use other method to test deprecation warnings
//      // proxy cares waring printing it in logs, and it's not passed to ror.
//      val indexResponse = dev1IndexManager.getIndex("index1" :: Nil, Map("include_type_name" -> "true"))
//
//      indexResponse.responseCode should be(200)
//      val warningHeader = indexResponse.headers.collectFirst { case SimpleHeader("Warning", value) => value }.get
//      warningHeader should include("[types removal] Using `include_type_name` in get indices requests is deprecated. The parameter will be removed in the next major version.")
//    }
//  }
//  "JWT auth and filter variable case" in {
//    val searchManager = new SearchManager(tokenAuthClient(
//      """Bearer eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ0ZXN0IiwidXNlcklkIjoidXNlcjUiLCJ1c2VyX2lkX2xpc3QiOlsiYWxpY2UiLCJib2IiXX0.aPtoDBPTVhtLPmwSKO6g41NEs7qhEeDG53e4aeHMQ66avoBblkUuDYBB2nFlQCxi90lfwXRzdkFYvjhtqijBP98uz6-bs8HmlfOG6_DoZRlWy5FLtdAS7F7UReqKtQ36KjNI7-YJtSTyaiDwymXPxiP44e4jJ3kJy1yx7r3ALmX7wbys1JGrUTddWQW0GWY8p2bf-hpmUmuu8AUGjfIOqYBBFWLT-NyuTYTMGUZlF8yxoBlp8twMVrqqT6ejLRQwgVxIoFL1g04uMwXUDit2dCzk5qTMAim3U-8Cgol7gi_yR-23BPY_pOejK9QPseXhpKQ9sW7v_jnLMuaI86jLhA"""
//    ))
//    val result = searchManager.search(
//      ujson.read("""{"query": {"terms":{"user_id": ["alice", "bob"]}}}""")
//    )
//
//    result.responseCode should be(200)
//    result.searchHits.size should be(1)
//    result.searchHits(0)("_source")("user_id").str should be("alice")
//  }
  "Cluster health response using response_fields rule should be filtered" in {
    val dev1ClusterStateManager = new ClusterManager(esTargets.head.basicAuthClient("dev1", "test"), esVersion = esTargets.head.esVersion)
    val healthCheck = dev1ClusterStateManager.health()

    println(healthCheck.responseJson.toString())
    healthCheck.responseJson.obj.isDefinedAt("cluster_name") should equal(true)
    healthCheck.responseJson.obj.isDefinedAt("number_of_nodes") should equal(false)
  }

  "Cat health response using response_fields rule should be filtered" in {
    val dev1ClusterStateManager = new CatManager(esTargets.head.basicAuthClient("dev1", "test"), esVersion = esTargets.head.esVersion)
    val healthCheck = dev1ClusterStateManager.healthCheck()

    println(healthCheck.responseJson.toString())
    healthCheck.responseJson.arr.head.obj.isDefinedAt("status") should equal(true)
    healthCheck.responseJson.arr.head.obj.isDefinedAt("cluster") should equal(false)
  }
}

object MiscSuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    documentManager.createDoc("index1", 1, ujson.read("""{"user_id":"ivan"}""")).force()
    documentManager.createDoc("index1", 2, ujson.read("""{"user_id":"alice"}""")).force()
  }
}