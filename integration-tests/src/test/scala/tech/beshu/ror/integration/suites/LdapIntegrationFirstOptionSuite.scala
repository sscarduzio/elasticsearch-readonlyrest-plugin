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
import tech.beshu.ror.utils.containers.generic.dependencies.ldap
import tech.beshu.ror.utils.containers.generic.{ElasticsearchNodeDataInitializer, EsClusterSettings, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{ElasticsearchTweetsInitializer, IndexManager}
import tech.beshu.ror.utils.httpclient.RestClient

//TODO change test names. Current names are copies from old java integration tests
trait LdapIntegrationFirstOptionSuite
  extends WordSpec
    with BaseIntegrationTest
    with SingleClientSupport
    with Matchers {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/ldap_integration_1st/readonlyrest.yml"

  override lazy val targetEs = container.nodesContainers.head

  override lazy val container = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      dependentServicesContainers = List(
        ldap(name = "LDAP1", ldapInitScript = "/ldap_integration_1st/ldap.ldif"),
        ldap(name = "LDAP2", ldapInitScript = "/ldap_integration_1st/ldap.ldif")
      ),
      nodeDataInitializer = LdapIntegrationFirstOptionSuite.nodeDataInitializer()
    )
  )

  "usersFromGroup1CanSeeTweets" in {
    val firstIndexManager = new IndexManager(basicAuthClient("cartman", "user2"))
    val firstResult = firstIndexManager.getIndex("twitter")

    firstResult.responseCode should be(200)

    val indexManager = new IndexManager(basicAuthClient("bong", "user1"))
    val response = indexManager.getIndex("twitter")

    response.responseCode should be(200)
  }
  "usersFromOutsideOfGroup1CannotSeeTweets" in {
    val indexManager = new IndexManager(basicAuthClient("morgan", "user1"))
    val response = indexManager.getIndex("twitter")

    response.responseCode should be(404)
  }
  "unauthenticatedUserCannotSeeTweets" in {
    val indexManager = new IndexManager(basicAuthClient("cartman", "wrong_password"))
    val response = indexManager.getIndex("twitter")

    response.responseCode should be(403)
  }
  "usersFromGroup3CanSeeFacebookPosts" in {
    val cartmanIndexManager = new IndexManager(basicAuthClient("cartman", "user2"))
    val cartmanResult = cartmanIndexManager.getIndex("facebook")

    cartmanResult.responseCode should be(200)

    val bongIndexManager = new IndexManager(basicAuthClient("bong", "user1"))
    val bongResult = bongIndexManager.getIndex("facebook")

    bongResult.responseCode should be(200)

    val morganIndexManager = new IndexManager(basicAuthClient("morgan", "user1"))
    val morganResult = morganIndexManager.getIndex("facebook")

    morganResult.responseCode should be(200)
  }
}

object LdapIntegrationFirstOptionSuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    new ElasticsearchTweetsInitializer().initialize(adminRestClient)
  }
}
