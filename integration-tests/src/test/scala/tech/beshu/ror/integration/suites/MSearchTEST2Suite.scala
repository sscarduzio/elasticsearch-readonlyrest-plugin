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
import tech.beshu.ror.integration.suites.base.support.{BaseIntegrationTest, SingleClientSupport}
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsClusterSettings, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{DocumentManagerJ, IndexManagerJ, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient

//TODO change test names. Current names are copies from old java integration tests
trait MSearchTEST2Suite
  extends WordSpec
    with BaseIntegrationTest
    with SingleClientSupport
    with Matchers {
  this: EsContainerCreator =>

  private val msearchBodyNoMatch =
    """{"index":["perfmon_logstash-apacheaccess*"]}
      |{"query":{"bool":{"must_not":[{"match_all":{}}]}}}
      |""".stripMargin

  private val msearchBroken =
    """{"index":["perfmon_endpoint_requests"]}
      |{"query":{"query_string":{"analyze_wildcard":true,"query":"*"}},"size":0}
      |""".stripMargin

  private val msearchBodyEmptyIndex =
    """{"index":["empty_index"],"ignore_unavailable":true,"preference":1506497937939}
      |{"query":{"bool":{"must_not":[{"match_all":{}}]}}}
      |""".stripMargin

  private val msearchBodyCombo = msearchBodyNoMatch + msearchBroken + msearchBodyEmptyIndex

  override implicit val rorConfigFileName = "/msearch_test2/readonlyrest.yml"

  override lazy val targetEs = container.nodesContainers.head

  override lazy val container = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      nodeDataInitializer = MSearchTEST2Suite.nodeDataInitializer()
    )
  )

  "test274_2" in {
    val searchManager = new SearchManager(basicAuthClient("kibana", "kibana"))

    val response = searchManager.msearch(msearchBodyNoMatch)

    response.responseCode shouldBe 200
    response.responses.size shouldBe 1
    response.totalHitsForResponse(0) shouldBe 0
  }

  "test274_2_broken" in {
    val searchManager = new SearchManager(basicAuthClient("kibana", "kibana"))

    val response = searchManager.msearch(msearchBroken)

    response.responseCode shouldBe 200
    response.responses.size shouldBe 1
    response.totalHitsForResponse(0) shouldBe 1
  }

  "test274_2_empty_index" in {
    val searchManager = new SearchManager(basicAuthClient("kibana", "kibana"))

    val response = searchManager.msearch(msearchBodyEmptyIndex)

    response.responseCode shouldBe 200
    response.responses.size shouldBe 1
    response.totalHitsForResponse(0) shouldBe 0
  }

  "test274_2_combo" in {
    val searchManager = new SearchManager(basicAuthClient("kibana", "kibana"))

    val response = searchManager.msearch(msearchBodyCombo)

    response.responseCode shouldBe 200
    response.responses.size shouldBe 3
    response.totalHitsForResponse(0) shouldBe 0
    response.totalHitsForResponse(1) shouldBe 1
    response.totalHitsForResponse(2) shouldBe 0
  }
}

object MSearchTEST2Suite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    val documentManager = new DocumentManagerJ(adminRestClient)
    val indexManager = new IndexManagerJ(adminRestClient)

    indexManager.create("empty_index")

    documentManager.insertDocAndWaitForRefresh(
      "/perfmon_endpoint_requests/documents/doc1",
      """{"id": "asd123"}"""
    )
    documentManager.insertDocAndWaitForRefresh(
      "perfmon_logstash-apacheaccess1/documents/doc1",
      """{"id": "asd123"}"""
    )
    documentManager.insertDocAndWaitForRefresh(
      "perfmon_logstash-apacheaccess1/documents/doc2",
      """{"id": "asd123"}"""
    )
  }
}