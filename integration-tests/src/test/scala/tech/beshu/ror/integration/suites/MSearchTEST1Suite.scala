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
import tech.beshu.ror.integration.suites.base.support.BasicSingleNodeEsClusterSupport
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{DocumentManagerJ, IndexManagerJ, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient

//TODO change test names. Current names are copies from old java integration tests
trait MSearchTEST1Suite
  extends WordSpec
    with BasicSingleNodeEsClusterSupport
    with Matchers {
  this: EsContainerCreator =>

  val msearchBodyNotExists =
    """{"index":["perfmon_index_does_not_exist"],"ignore_unavailable":true,"preference":1506497937939}
      |{"query":{"bool":{"must_not":[{"match_all":{}}]}}}
      |""".stripMargin

  val msearchBodyQueryWorks =
    """{"index":[".kibana"],"ignore_unavailable":true,"preference":1506497937939}
      |{"query":{"match_all":{}}, "size":0}
      |""".stripMargin

  val msearchBodyEmptyIndex =
    """{"index":[".kibana"],"ignore_unavailable":true,"preference":1506497937939}
      |{"query":{"bool":{"must_not":[{"match_all":{}}]}}}
      |""".stripMargin

  val msearchBodyCombo = msearchBodyNotExists + msearchBodyQueryWorks + msearchBodyEmptyIndex

  override implicit val rorConfigFileName = "/msearch_test1/readonlyrest.yml"

  override def nodeDataInitializer = Some(MSearchTEST1Suite.nodeDataInitializer())

  "test274_1_notexist" in {
    val searchManager = new SearchManager(basicAuthClient("kibana", "kibana"))

    val response = searchManager.mSearch(msearchBodyNotExists)

    response.responseCode shouldBe 401
  }

  "test274_1_queryworks" in {
    val searchManager = new SearchManager(basicAuthClient("kibana", "kibana"))

    val response = searchManager.mSearch(msearchBodyQueryWorks)

    response.responseCode shouldBe 200
    response.responses.size shouldBe 1
    response.totalHitsForResponse(0) shouldBe 1
  }

  "test274_1_empty_index" in {
    val searchManager = new SearchManager(basicAuthClient("kibana", "kibana"))

    val response = searchManager.mSearch(msearchBodyEmptyIndex)

    response.responseCode shouldBe 200
    response.responses.size shouldBe 1
    response.totalHitsForResponse(0) shouldBe 0
  }

  "test274_1_all" in {
    val searchManager = new SearchManager(basicAuthClient("kibana", "kibana"))

    val response = searchManager.mSearch(msearchBodyCombo)

    response.responseCode shouldBe 200
    response.responses.size shouldBe 3
    response.totalHitsForResponse(0) shouldBe 0
    response.totalHitsForResponse(1) shouldBe 1
    response.totalHitsForResponse(2) shouldBe 0
  }
}

object MSearchTEST1Suite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    val documentManager = new DocumentManagerJ(adminRestClient)
    val indexManager = new IndexManagerJ(adminRestClient)

    indexManager.create("empty_index")
    documentManager.insertDocAndWaitForRefresh(
      "/.kibana/documents/doc1",
      """{"id": "asd123"}"""
    )
  }
}