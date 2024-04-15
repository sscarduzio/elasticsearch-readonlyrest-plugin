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
import tech.beshu.ror.utils.containers._
import tech.beshu.ror.utils.containers.dependencies.wiremock
import tech.beshu.ror.utils.elasticsearch.{ElasticsearchTweetsInitializer, IndexManager}
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers

//todo: change test names. Current names are copies from old java integration tests
class ReverseProxyAuthenticationWithGroupsProviderAuthorizationSuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport
    with ESVersionSupportForAnyWordSpecLike
    with CustomScalaTestMatchers {

  override implicit val rorConfigFileName: String = "/rev_proxy_groups_provider/readonlyrest.yml"

  override def nodeDataInitializer: Option[ElasticsearchNodeDataInitializer] = Some(ElasticsearchTweetsInitializer)

  override def clusterDependencies: List[DependencyDef] = List(
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
  )

  "testAuthenticationAndAuthorizationSuccessWithService1" in {
    val indexManager = new IndexManager(
      client = noBasicAuthClient,
      esVersionUsed,
      additionalHeaders = Map("X-Auth-Token" -> "cartman"))

    val result = indexManager.getIndex("twitter")

    result should have statusCode 200
  }

  "testAuthenticationAndAuthorizationErrorWithService1" in {
    val indexManager = new IndexManager(
      client = noBasicAuthClient,
      esVersionUsed,
      additionalHeaders = Map("X-Auth-Token" -> "morgan"))

    val result = indexManager.getIndex("twitter")

    result should have statusCode 403
  }

  "testAuthenticationAndAuthorizationSuccessWithService2" in {
    val indexManager = new IndexManager(
      client = noBasicAuthClient,
      esVersionUsed,
      additionalHeaders = Map("X-Auth-Token" -> "29b3d166-1952-11e7-8b77-6c4008a76fc6"))

    val result = indexManager.getIndex("facebook")

    result should have statusCode 200
  }
}
