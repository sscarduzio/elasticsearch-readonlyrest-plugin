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
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, IndexManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers

//TODO change test names. Current names are copies from old java integration tests
class MSearchTEST2Suite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport
    with ESVersionSupportForAnyWordSpecLike
    with CustomScalaTestMatchers {

  private val msearchBodyNoMatch = Seq(
    """{"index":["perfmon_logstash-apacheaccess*"]}""",
    """{"query":{"bool":{"must_not":[{"match_all":{}}]}}}"""
  )

  private val msearchBroken = Seq(
    """{"index":["perfmon_endpoint_requests"]}""",
    """{"query":{"query_string":{"analyze_wildcard":true,"query":"*"}},"size":0}"""
  )

  private val msearchBodyEmptyIndex = Seq(
    """{"index":["empty_index"],"ignore_unavailable":true,"preference":1506497937939}""",
    """{"query":{"bool":{"must_not":[{"match_all":{}}]}}}"""
  )

  private val msearchBodyCombo = msearchBodyNoMatch ++ msearchBroken ++ msearchBodyEmptyIndex

  override implicit val rorConfigFileName: String = "/msearch_test2/readonlyrest.yml"

  override def nodeDataInitializer = Some(MSearchTEST2Suite.nodeDataInitializer())

  "test274_2" in {
    val searchManager = new SearchManager(basicAuthClient("kibana", "kibana"))

    val response = searchManager.mSearchUnsafe(msearchBodyNoMatch: _*)

    response should have statusCode 200
    response.responses.size shouldBe 1
    response.totalHitsForResponse(0) shouldBe 0
  }

  "test274_2_broken" in {
    val searchManager = new SearchManager(basicAuthClient("kibana", "kibana"))

    val response = searchManager.mSearchUnsafe(msearchBroken: _*)

    response should have statusCode 200
    response.responses.size shouldBe 1
    response.totalHitsForResponse(0) shouldBe 1
  }

  "test274_2_empty_index" in {
    val searchManager = new SearchManager(basicAuthClient("kibana", "kibana"))

    val response = searchManager.mSearchUnsafe(msearchBodyEmptyIndex: _*)

    response should have statusCode 200
    response.responses.size shouldBe 1
    response.totalHitsForResponse(0) shouldBe 0
  }

  "test274_2_combo" in {
    val searchManager = new SearchManager(basicAuthClient("kibana", "kibana"))

    val response = searchManager.mSearchUnsafe(msearchBodyCombo: _*)

    response should have statusCode 200
    response.responses.size shouldBe 3
    response.totalHitsForResponse(0) shouldBe 0
    response.totalHitsForResponse(1) shouldBe 1
    response.totalHitsForResponse(2) shouldBe 0
  }
}

object MSearchTEST2Suite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    val indexManager = new IndexManager(adminRestClient, esVersion)

    indexManager.createIndex("empty_index").force()
    documentManager
      .createDoc("perfmon_endpoint_requests", "documents", 1, ujson.read("""{"id": "asd123"}"""))
      .force()
    documentManager
      .createDoc("perfmon_logstash-apacheaccess1", "documents", 1, ujson.read("""{"id": "asd123"}"""))
      .force()
    documentManager
      .createDoc("perfmon_logstash-apacheaccess1", "documents", 2, ujson.read("""{"id": "asd123"}"""))
      .force()
  }
}