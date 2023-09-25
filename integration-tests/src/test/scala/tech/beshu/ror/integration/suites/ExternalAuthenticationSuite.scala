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
import tech.beshu.ror.utils.containers.dependencies.wiremock
import tech.beshu.ror.utils.containers.{DependencyDef, ElasticsearchNodeDataInitializer}
import tech.beshu.ror.utils.elasticsearch.{ElasticsearchTweetsInitializer, IndexManager}
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers

//TODO change test names. Current names are copies from old java integration tests
class ExternalAuthenticationSuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport
    with ESVersionSupportForAnyWordSpecLike
    with CustomScalaTestMatchers {

  override implicit val rorConfigFileName: String = "/external_authentication/readonlyrest.yml"

  override def nodeDataInitializer: Option[ElasticsearchNodeDataInitializer] = Some(ElasticsearchTweetsInitializer)

  override def clusterDependencies: List[DependencyDef] = List(
    wiremock(name = "EXT1", mappings = "/external_authentication/wiremock_service1_cartman.json", "/external_authentication/wiremock_service1_morgan.json"),
    wiremock(name = "EXT2", mappings = "/external_authentication/wiremock_service2_cartman.json")
  )

  "testAuthenticationSuccessWithService1" in {
    val indexManager = new IndexManager(basicAuthClient("cartman", "user1"), esVersionUsed)
    val response = indexManager.getIndex("twitter")

    response should have statusCode 200
  }
  "testAuthenticationSuccessWithService2" in {
    val indexManager = new IndexManager(basicAuthClient("cartman", "user1"), esVersionUsed)
    val response = indexManager.getIndex("facebook")

    response should have statusCode 200
  }
  "testAuthenticationErrorWithService1" in {
    val firstIndexManager = new IndexManager(basicAuthClient("cartman", "user2"), esVersionUsed)
    val firstResult = firstIndexManager.getIndex("twitter")

    firstResult should have statusCode 403

    val indexManager = new IndexManager(basicAuthClient("morgan", "user2"), esVersionUsed)
    val response = indexManager.getIndex("twitter")

    response should have statusCode 403
  }
}
