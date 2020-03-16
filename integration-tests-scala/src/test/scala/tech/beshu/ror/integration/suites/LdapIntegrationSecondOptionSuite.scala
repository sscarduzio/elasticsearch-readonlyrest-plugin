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

import monix.eval.Coeval
import org.scalatest.{Matchers, WordSpec}
import tech.beshu.ror.integration.suites.base.support.{BaseIntegrationTest, SingleClientSupport}
import tech.beshu.ror.utils.containers.LdapContainer
import tech.beshu.ror.utils.containers.generic.{DependencyDef, ElasticsearchNodeDataInitializer, EsClusterSettings, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{ElasticsearchTweetsInitializer, IndexManagerJ}
import tech.beshu.ror.utils.httpclient.RestClient

trait LdapIntegrationSecondOptionSuite
  extends WordSpec
    with BaseIntegrationTest
    with SingleClientSupport
    with Matchers {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/ldap_integration_2nd/readonlyrest.yml"

  override lazy val targetEs = container.nodesContainers.head

  override lazy val container = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      dependentServicesContainers = List(
        DependencyDef(name = "LDAP1", Coeval(new LdapContainer("LDAP1", "/ldap_integration_2nd/ldapNew.ldif"))),
        DependencyDef(name = "LDAP2", Coeval(new LdapContainer("LDAP2", "/ldap_integration_2nd/ldapNew.ldif"))),
      ),
      nodeDataInitializer = LdapIntegrationSecondOptionSuite.nodeDataInitializer()
    )
  )

  "usersFromGroup1CanSeeTweets" in {
    val firstIndexManager = new IndexManagerJ(basicAuthClient("cartman", "user2"))
    val firstResult = firstIndexManager.get("twitter")

    firstResult.getResponseCode should be(200)

    val indexManager = new IndexManagerJ(basicAuthClient("bong", "user1"))
    val response = indexManager.get("twitter")

    response.getResponseCode should be(200)
  }
  "usersFromOutsideOfGroup1CannotSeeTweets" in {
    val indexManager = new IndexManagerJ(basicAuthClient("morgan", "user1"))
    val response = indexManager.get("twitter")

    response.getResponseCode should be(404)
  }
  "unauthenticatedUserCannotSeeTweets" in {
    val indexManager = new IndexManagerJ(basicAuthClient("cartman", "wrong_password"))
    val response = indexManager.get("twitter")

    response.getResponseCode should be(403)
  }
  "usersFromGroup3CanSeeFacebookPosts" in {
    val cartmanIndexManager = new IndexManagerJ(basicAuthClient("cartman", "user2"))
    val cartmanResult = cartmanIndexManager.get("facebook")

    cartmanResult.getResponseCode should be(200)

    val bongIndexManager = new IndexManagerJ(basicAuthClient("bong", "user1"))
    val bongResult = bongIndexManager.get("facebook")

    bongResult.getResponseCode should be(200)

    val morganIndexManager = new IndexManagerJ(basicAuthClient("morgan", "user1"))
    val morganResult = morganIndexManager.get("facebook")

    morganResult.getResponseCode should be(200)
  }
}

object LdapIntegrationSecondOptionSuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    new ElasticsearchTweetsInitializer().initialize(adminRestClient)
  }
}


