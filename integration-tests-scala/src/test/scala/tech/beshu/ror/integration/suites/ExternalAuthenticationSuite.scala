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
import tech.beshu.ror.utils.containers.generic._
import tech.beshu.ror.utils.containers.generic.dependencies.wiremock
import tech.beshu.ror.utils.elasticsearch.{ElasticsearchTweetsInitializer, IndexManagerJ}
import tech.beshu.ror.utils.httpclient.RestClient

trait ExternalAuthenticationSuite
  extends WordSpec
    with BaseIntegrationTest
    with SingleClientSupport
    with Matchers {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/external_authentication/readonlyrest.yml"

  override lazy val targetEs = container.nodesContainers.head

  override lazy val container = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      dependentServicesContainers = List(
        wiremock(name = "EXT1", mappings = "/external_authentication/wiremock_service1_cartman.json", "/external_authentication/wiremock_service1_morgan.json"),
        wiremock(name = "EXT2", mappings = "/external_authentication/wiremock_service2_cartman.json")
      ),
      nodeDataInitializer = ExternalAuthenticationSuite.nodeDataInitializer()
    )
  )

  "testAuthenticationSuccessWithService1" in {
    val indexManager = new IndexManagerJ(basicAuthClient("cartman", "user1"))
    val response = indexManager.get("twitter")

    response.getResponseCode should be(200)
  }
  "testAuthenticationSuccessWithService2" in {
    val indexManager = new IndexManagerJ(basicAuthClient("cartman", "user1"))
    val response = indexManager.get("facebook")

    response.getResponseCode should be(200)
  }
  "testAuthenticationErrorWithService1" in {
    val firstIndexManager = new IndexManagerJ(basicAuthClient("cartman", "user2"))
    val firstResult = firstIndexManager.get("twitter")

    firstResult.getResponseCode should be(403)

    val indexManager = new IndexManagerJ(basicAuthClient("morgan", "user2"))
    val response = indexManager.get("twitter")

    response.getResponseCode should be(403)
  }
}

object ExternalAuthenticationSuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    new ElasticsearchTweetsInitializer().initialize(adminRestClient)
  }
}