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
import tech.beshu.ror.utils.containers.dependencies.ldap
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsClusterContainer, EsClusterSettings, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{ElasticsearchTweetsInitializer, IndexManager}
import tech.beshu.ror.utils.httpclient.RestClient

//TODO change test names. Current names are copies from old java integration tests
trait LdapAuthenticationLocalAuthorizationSuite
  extends WordSpec
    with BaseEsClusterIntegrationTest
    with SingleClientSupport
    with Matchers {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/ldap_authc_local_authz/readonlyrest.yml"

  override lazy val targetEs = container.nodes.head

  override lazy val clusterContainer: EsClusterContainer = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      dependentServicesContainers = List(
        ldap(name = "LDAP1", ldapInitScript =  "/ldap_authc_local_authz/ldap.ldif")
      ),
      nodeDataInitializer = LdapAuthenticationLocalAuthorizationSuite.nodeDataInitializer()
    )
  )

  "checkCartmanCanSeeTwitter" in {
    val indexManager = new IndexManager(basicAuthClient("cartman", "user2"))
    val result = indexManager.getIndex("twitter")

    result.responseCode should be(200)
  }
  "checkUnicodedBibloCanSeeTwitter" in {
    val indexManager = new IndexManager(basicAuthClient("Bìlbö Bággįnš", "user2"))
    val result = indexManager.getIndex("twitter")

    result.responseCode should be(200)
  }
  "checkMorganCanSeeFacebook" in {
    val indexManager = new IndexManager(basicAuthClient("morgan", "user1"))
    val result = indexManager.getIndex("facebook")

    result.responseCode should be(200)
  }
  "checkMorganCannotSeeTwitter" in {
    val indexManager = new IndexManager(basicAuthClient("morgan", "user1"))
    val result = indexManager.getIndex("twitter")

    result.responseCode should be(404)
  }
  "checkCartmanCannotSeeFacebook" in {
    val indexManager = new IndexManager(basicAuthClient("cartman", "user2"))
    val result = indexManager.getIndex("facebook")

    result.responseCode should be(404)
  }
}

object LdapAuthenticationLocalAuthorizationSuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    new ElasticsearchTweetsInitializer().initialize(adminRestClient)
  }
}
