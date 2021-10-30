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
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.integration.utils.ESVersionSupportForAnyWordSpecLike
import tech.beshu.ror.utils.containers._
import tech.beshu.ror.utils.containers.dependencies.wiremock
import tech.beshu.ror.utils.elasticsearch.{ElasticsearchTweetsInitializer, IndexManager}

//TODO: change test names. Current names are copies from old java integration tests
trait ReverseProxyAuthenticationWithGroupsProviderAuthorizationSuite
  extends AnyWordSpec
    with BaseEsClusterIntegrationTest
    with ESVersionSupportForAnyWordSpecLike
    with SingleClientSupport
    with Matchers {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/rev_proxy_groups_provider/readonlyrest.yml"

  override lazy val targetEs = container.nodes.head

  override lazy val clusterContainer: EsClusterContainer = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      dependentServicesContainers = List(
        wiremock(
          name = "GROUPS1",
          mappings =
            "/rev_proxy_groups_provider/wiremock_service1_cartman.json",
            "/rev_proxy_groups_provider/wiremock_service1_morgan.json",
            "/rev_proxy_groups_provider/wiremock_service1_anyuser.json"
        ),
        wiremock(
          name = "GROUPS2",
          mappings =
            "/rev_proxy_groups_provider/wiremock_service2_token.json",
            "/rev_proxy_groups_provider/wiremock_service2_anytoken.json",
        )
      ),
      nodeDataInitializer = ElasticsearchTweetsInitializer,
      xPackSupport = false,
    )
  )

  "testAuthenticationAndAuthorizationSuccessWithService1" in {
    val indexManager = new IndexManager(
      client = noBasicAuthClient,
      esVersionUsed,
      additionalHeaders = Map("X-Auth-Token" -> "cartman"))

    val result = indexManager.getIndex("twitter")

    result.responseCode should be(200)
  }

  "testAuthenticationAndAuthorizationErrorWithService1" in {
    val indexManager = new IndexManager(
      client = noBasicAuthClient,
      esVersionUsed,
      additionalHeaders = Map("X-Auth-Token" -> "morgan"))

    val result = indexManager.getIndex("twitter")

    result.responseCode should be(403)
  }

  "testAuthenticationAndAuthorizationSuccessWithService2" in {
    val indexManager = new IndexManager(
      client = noBasicAuthClient,
      esVersionUsed,
      additionalHeaders = Map("X-Auth-Token" -> "29b3d166-1952-11e7-8b77-6c4008a76fc6"))

    val result = indexManager.getIndex("facebook")

    result.responseCode should be(200)
  }
}
