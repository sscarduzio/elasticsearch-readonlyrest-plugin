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
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, IndexManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient

//TODO change test names. Current names are copies from old java integration tests
trait MSearchTEST1Suite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with Matchers {
  this: EsContainerCreator =>

  val msearchBodyNotExists = Seq(
    """{"index":["perfmon_index_does_not_exist"],"ignore_unavailable":true,"preference":1506497937939}""",
    """{"query":{"bool":{"must_not":[{"match_all":{}}]}}}"""
  )

  val msearchBodyQueryWorks = Seq(
    """{"index":[".kibana"],"ignore_unavailable":true,"preference":1506497937939}""",
    """{"query":{"match_all":{}}, "size":0}"""
  )

  val msearchBodyEmptyIndex = Seq(
    """{"index":[".kibana"],"ignore_unavailable":true,"preference":1506497937939}""",
    """{"query":{"bool":{"must_not":[{"match_all":{}}]}}}"""
  )

  val msearchBodyCombo = msearchBodyNotExists ++ msearchBodyQueryWorks ++ msearchBodyEmptyIndex

  override implicit val rorConfigFileName = "/msearch_test1/readonlyrest.yml"

  override def nodeDataInitializer = Some(MSearchTEST1Suite.nodeDataInitializer())

  "test274_1_notexist" in {
    val searchManager = new SearchManager(basicAuthClient("kibana", "kibana"))

    val response = searchManager.mSearchUnsafe(msearchBodyNotExists: _*)

    response.searchHitsForResponse(0) should be (Vector.empty)
  }

  "test274_1_queryworks" in {
    val searchManager = new SearchManager(basicAuthClient("kibana", "kibana"))

    val response = searchManager.mSearchUnsafe(msearchBodyQueryWorks: _*)

    response.responseCode shouldBe 200
    response.responses.size shouldBe 1
    response.totalHitsForResponse(0) shouldBe 1
  }

  "test274_1_empty_index" in {
    val searchManager = new SearchManager(basicAuthClient("kibana", "kibana"))

    val response = searchManager.mSearchUnsafe(msearchBodyEmptyIndex: _*)

    response.responseCode shouldBe 200
    response.responses.size shouldBe 1
    response.totalHitsForResponse(0) shouldBe 0
  }

  "test274_1_all" in {
    val searchManager = new SearchManager(basicAuthClient("kibana", "kibana"))

    val response = searchManager.mSearchUnsafe(msearchBodyCombo: _*)

    response.responseCode shouldBe 200
    response.responses.size shouldBe 3
    response.totalHitsForResponse(0) shouldBe 0
    response.totalHitsForResponse(1) shouldBe 1
    response.totalHitsForResponse(2) shouldBe 0
  }
}

object MSearchTEST1Suite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    val indexManager = new IndexManager(adminRestClient, esVersion)

    indexManager.createIndex("empty_index").force()
    documentManager.createDoc(".kibana", "documents", 1, ujson.read("""{"id": "asd123"}""")).force()
  }
}