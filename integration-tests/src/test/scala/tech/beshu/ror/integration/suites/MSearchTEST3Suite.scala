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
import tech.beshu.ror.utils.containers.generic.{ElasticsearchNodeDataInitializer, EsClusterSettings, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{DocumentManagerJ, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient

//TODO change test names. Current names are copies from old java integration tests
trait MSearchTEST3Suite
  extends WordSpec
    with BaseIntegrationTest
    with SingleClientSupport
    with Matchers {
  this: EsContainerCreator =>

  private val msearchBodyTryMatchBoth =
    """{"index":["monit_private*"]}
      |{"version":true,"size":0,"query":{"match_all":{}}}
      |""".stripMargin

  override implicit val rorConfigFileName = "/msearch_test3/readonlyrest.yml"

  override lazy val targetEs = container.nodesContainers.head

  override lazy val container = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      nodeDataInitializer = MSearchTEST3Suite.nodeDataInitializer()
    )
  )

  "testMgetWildcard" in {
    val searchManager = new SearchManager(
      client = basicAuthClient("justOverrideAdminCredentials", "random09310+23"),
      additionalHeaders = Map("X-Forwarded-For" -> "elastic.co")
    )

    val response = searchManager.msearch(msearchBodyTryMatchBoth)

    response.responseCode shouldBe 200
    response.responses.size shouldBe 1
    response.totalHitsForResponse(0) shouldBe 1
  }
}

object MSearchTEST3Suite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    val documentManager = new DocumentManagerJ(adminRestClient)

    documentManager.insertDocAndWaitForRefresh(
      "/monit_private_hammercloud_2/documents/docHC2",
      """{"id": "docHC2"}"""
    )
    documentManager.insertDocAndWaitForRefresh(
      "monit_private_openshift/documents/docOS",
      """{"id": "docOS"}"""
    )
  }
}