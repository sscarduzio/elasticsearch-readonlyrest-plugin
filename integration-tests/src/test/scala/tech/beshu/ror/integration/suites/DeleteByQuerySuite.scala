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
import tech.beshu.ror.utils.elasticsearch.{DeleteByQueryManagerJ, ElasticsearchTweetsInitializer}
import tech.beshu.ror.utils.httpclient.RestClient

trait DeleteByQuerySuite
  extends WordSpec
    with BaseIntegrationTest
    with SingleClientSupport
    with Matchers {
  this: EsContainerCreator =>

  private val matchAllQuery = """{"query" : {"match_all" : {}}}""".stripMargin

  override implicit val rorConfigFileName = "/delete_by_query/readonlyrest.yml"

  override lazy val targetEs = container.nodesContainers.head

  override lazy val container = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      nodeDataInitializer = DeleteByQuerySuite.nodeDataInitializer()
    )
  )

  private lazy val blueTeamDeleteByQueryManager = new DeleteByQueryManagerJ(basicAuthClient("blue", "dev"))
  private lazy val redTeamDeleteByQueryManager = new DeleteByQueryManagerJ(basicAuthClient("red", "dev"))

  "Delete by query" should {
    "be allowed" when {
      "is executed by blue client" in {
        val response = blueTeamDeleteByQueryManager.delete("twitter", matchAllQuery)
        response.getResponseCode shouldBe 200
      }
    }
    "not be allowed" when {
      "is executed by red client" in {
        val response = redTeamDeleteByQueryManager.delete("facebook", matchAllQuery)
        response.getResponseCode shouldBe 401
      }
    }
  }
}

object DeleteByQuerySuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    new ElasticsearchTweetsInitializer().initialize(adminRestClient)
  }
}