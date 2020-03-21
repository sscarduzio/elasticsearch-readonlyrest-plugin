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

trait LdapIntegrationGroupsSuite
  extends WordSpec
    with BaseIntegrationTest
    with SingleClientSupport
    with Matchers {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/ldap_integration_group_headers/readonlyrest.yml"

  override lazy val targetEs = container.nodesContainers.head

  override lazy val container = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      dependentServicesContainers = List(
        ldap(name = "LDAP1", ldapInitScript =  "/ldap_integration_group_headers/ldap.ldif")
      ),
      nodeDataInitializer = LdapIntegrationGroupsSuite.nodeDataInitializer()
    )
  )

  "checkCartmanWithoutCurrGroupHeader" in {
    val indexManager = new IndexManager(basicAuthClient("cartman", "user2"))

    val result = indexManager.getIndex("twitter")

    result.responseCode should be(200)
  }
  "checkCartmanWithGroup1AsCurrentGroup" in {
    val indexManager = new IndexManager(
      client = basicAuthClient("cartman", "user2"),
      additionalHeaders = Map("x-ror-current-group" -> "group1"))

    val result = indexManager.getIndex("twitter")

    result.responseCode should be(200)
  }
  "checkCartmanWithGroup3AsCurrentGroup" in {
    val indexManager = new IndexManager(
      client = basicAuthClient("cartman", "user2"),
      additionalHeaders = Map("x-ror-current-group" -> "group3"))

    val result = indexManager.getIndex("twitter")

    result.responseCode should be(200)
  }
}

object LdapIntegrationGroupsSuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    new ElasticsearchTweetsInitializer().initialize(adminRestClient)
  }
}
