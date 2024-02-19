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
package tech.beshu.ror.integration

import com.dimafeng.testcontainers.{ForAllTestContainer, MultipleContainers}
import eu.timepit.refined.auto._
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAll, Inside}
import tech.beshu.ror.accesscontrol.AccessControl.UserMetadataRequestResult._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.factory.{AsyncHttpClientsFactory, HttpClientsFactory}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.containers.LdapContainer

class CurrentUserMetadataAccessControlTests2
  extends AnyWordSpec
    with BaseYamlLoadedAccessControlTest
    with BeforeAndAfterAll
    with ForAllTestContainer
    with MockFactory
    with Inside {

  private val ldap1 = LdapContainer.create("LDAP1",
    "current_user_metadata_access_control_tests/ldap_ldap1_user5.ldif"
  )

  override val container: MultipleContainers = MultipleContainers(ldap1)

  override protected def afterAll(): Unit = {
    super.afterAll()
    ldapConnectionPoolProvider.close()
    httpClientsFactory.shutdown()
  }

  override protected val ldapConnectionPoolProvider = new UnboundidLdapConnectionPoolProvider

  override protected val httpClientsFactory: HttpClientsFactory = new AsyncHttpClientsFactory

  override protected def configYaml: String =
    s"""
      |readonlyrest:
      |
      |  access_control_rules:
      |
      |  - name: "LDAP1 user5 (3)"
      |    ldap_auth:
      |      name: "Ldap1"
      |      groups: ["ldap1_group1"]
      |
      |  ldaps:
      |  - name: Ldap1
      |    host: "${ldap1.ldapHost}"
      |    port: ${ldap1.ldapPort}
      |    ssl_enabled: false                                        # default true
      |    ssl_trust_all_certs: true                                 # default false
      |
      |    bind_dn: "cn=admin,dc=example,dc=com"                     # skip for anonymous bind
      |    bind_password: "password"                                 # skip for anonymous bind
      |    search_user_base_DN: "ou=People,dc=example,dc=com"
      |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
      |    user_id_attribute: "uid"                                  # default "uid"
      |    unique_member_attribute: "uniqueMember"                   # default "uniqueMember"
      |    cache_ttl_in_sec: 60                                      # default 0 - cache disabled
      |
    """.stripMargin

  "An ACL" when {
    "handling current user metadata kibana plugin request" should {
      "allow to proceed" when {
        "several blocks are matched" in {
          val request = MockRequestContext.metadata.copy(headers = Set(basicAuthHeader("user5:user2")))
          val result = acl.handleMetadataRequest(request).runSyncUnsafe()
          inside(result.result) { case Allow(userMetadata, _) =>
            userMetadata.loggedUser should be (Some(DirectlyLoggedUser(User.Id("user1"))))
            userMetadata.currentGroupId should be (Some(GroupId("group3")))
            userMetadata.availableGroups.toSet should be (Set(group("group3"), group("group1")))
            userMetadata.kibanaIndex should be (None)
            userMetadata.hiddenKibanaApps should be (Set.empty)
            userMetadata.allowedKibanaApiPaths should be (Set.empty)
            userMetadata.kibanaAccess should be (None)
            userMetadata.userOrigin should be (None)
          }
        }
      }
    }
  }
}
