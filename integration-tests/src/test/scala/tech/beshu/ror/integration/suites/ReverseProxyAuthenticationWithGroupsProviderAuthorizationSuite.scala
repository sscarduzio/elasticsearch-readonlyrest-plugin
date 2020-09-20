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
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.utils.containers.dependencies.wiremock
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsClusterContainer, EsClusterSettings, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{ElasticsearchTweetsInitializer, IndexManager}
import tech.beshu.ror.utils.httpclient.RestClient

//TODO change test names. Current names are copies from old java integration tests
trait ReverseProxyAuthenticationWithGroupsProviderAuthorizationSuite
  extends WordSpec
    with BaseEsClusterIntegrationTest
    with SingleClientSupport
    with Matchers {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/rev_proxy_groups_provider/readonlyrest.yml"

  override lazy val targetEs = container.nodes.head

  override lazy val clusterContainer: EsClusterContainer = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      dependentServicesContainers = List(
        wiremock(name = "GROUPS1", mappings =
          "/rev_proxy_groups_provider/wiremock_service1_cartman.json",
          "/rev_proxy_groups_provider/wiremock_service1_morgan.json"),
        wiremock(name = "GROUPS2", mappings =
          "/rev_proxy_groups_provider/wiremock_service2.json")
      ),
      nodeDataInitializer = ReverseProxyAuthenticationWithGroupsProviderAuthorizationSuite.nodeDataInitializer(),
      xPackSupport = isUsingXpackSupport,
    )
  )

  "testAuthenticationAndAuthorizationSuccessWithService1" in {
    val indexManager = new IndexManager(
      client = noBasicAuthClient,
      additionalHeaders = Map("X-Auth-Token" -> "cartman"))

    val result = indexManager.getIndex("twitter")

    result.responseCode should be(200)
  }

  "testAuthenticationAndAuthorizationErrorWithService1" in {
    val indexManager = new IndexManager(
      client = noBasicAuthClient,
      additionalHeaders = Map("X-Auth-Token" -> "morgan"))

    val result = indexManager.getIndex("twitter")

    result.responseCode should be(403)
  }

  "testAuthenticationAndAuthorizationSuccessWithService2" in {
    val indexManager = new IndexManager(
      client = noBasicAuthClient,
      additionalHeaders = Map("X-Auth-Token" -> "29b3d166-1952-11e7-8b77-6c4008a76fc6"))

    val result = indexManager.getIndex("facebook")

    result.responseCode should be(200)
  }
}

object ReverseProxyAuthenticationWithGroupsProviderAuthorizationSuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    new ElasticsearchTweetsInitializer().initialize(adminRestClient)
  }
}