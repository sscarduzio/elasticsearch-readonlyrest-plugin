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
import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAll, Inside}
import tech.beshu.ror.accesscontrol.AccessControlList.{ForbiddenCause, RegularRequestResult}
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.User
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.SingletonLdapContainers
import tech.beshu.ror.utils.TestsUtils.{basicAuthHeader, unsafeNes}

class LdapConnectivityCheckYamlLoadedAccessControlTests
  extends AnyWordSpec
    with BaseYamlLoadedAccessControlTest
    with BeforeAndAfterAll
    with ForAllTestContainer
    with Inside {

  override val container: MultipleContainers = MultipleContainers(
    SingletonLdapContainers.ldap1,
    SingletonLdapContainers.ldap2
  )

  override protected def configYaml: String =
    s"""readonlyrest:
       |
       |  access_control_rules:
       |
       |    - name: "LDAP1"
       |      ldap_authentication: "ldap1"
       |
       |    - name: "LDAP2"
       |      ldap_authentication: "ldap2"
       |
       |    - name: "LDAP3"
       |      ldap_authentication: "nonreachable_ldap"
       |
       |  ldaps:
       |    - name: ldap1
       |      servers:
       |        - "ldap://${SingletonLdapContainers.ldap1.ldapHost}:${SingletonLdapContainers.ldap1.ldapPort}"
       |        - "ldap://localhost:666"                                # doesn't work
       |      ha: ROUND_ROBIN
       |      ssl_enabled: false                                        # default true
       |      ssl_trust_all_certs: true                                 # default false
       |      bind_dn: "cn=admin,dc=example,dc=com"                     # skip for anonymous bind
       |      bind_password: "password"                                 # skip for anonymous bind
       |      users:
       |        search_user_base_DN: "ou=People,dc=example,dc=com"
       |        user_id_attribute: "uid"                                # default "uid
       |
       |    - name: ldap2
       |      host: "${SingletonLdapContainers.ldap2.ldapHost}"
       |      port: ${SingletonLdapContainers.ldap2.ldapPort}
       |      ignore_ldap_connectivity_problems: true
       |      ssl_enabled: false                                        # default true
       |      ssl_trust_all_certs: true                                 # default false
       |      bind_dn: "cn=admin,dc=example,dc=com"                     # skip for anonymous bind
       |      bind_password: "password"                                 # skip for anonymous bind
       |      users:
       |        search_user_base_DN: "ou=People,dc=example,dc=com"
       |        user_id_attribute: "uid"                                # default "uid
       |
       |    - name: nonreachable_ldap
       |      host: "localhost"
       |      port: 555
       |      ignore_ldap_connectivity_problems: true
       |      ssl_enabled: false                                        # default true
       |      ssl_trust_all_certs: true                                 # default false
       |      bind_dn: "cn=admin,dc=example,dc=com"                     # skip for anonymous bind
       |      bind_password: "password"                                 # skip for anonymous bind
       |      users:
       |        search_user_base_DN: "ou=People,dc=example,dc=com"
       |        user_id_attribute: "uid"                                # default "uid
       |""".stripMargin

  override protected def afterAll(): Unit = {
    super.afterAll()
    ldapConnectionPoolProvider.close().runSyncUnsafe()
  }

  override protected val ldapConnectionPoolProvider = new UnboundidLdapConnectionPoolProvider

  "LDAP authentication" should {
    "be successful" when {
      "one server is unreachable, but is configured to ignore connectivity problems" when {
        "HA is enabled and one of LDAP hosts is unavailable" in {
          val request = MockRequestContext.indices.withHeaders(basicAuthHeader("cartman:user2"))
          val result = acl.handleRegularRequest(request).runSyncUnsafe()
          result.history should have size 1
          inside(result.result) { case RegularRequestResult.Allow(blockContext, block) =>
            block.name should be(Block.Name("LDAP1"))
            assertBlockContext(loggedUser = Some(DirectlyLoggedUser(User.Id("cartman")))) {
              blockContext
            }
          }
        }
        "ROR is configured to ignore connectivity problems, but connection is possible" in {
          val request = MockRequestContext.indices.withHeaders(basicAuthHeader("kyle:user2"))
          val result = acl.handleRegularRequest(request).runSyncUnsafe()
          result.history should have size 2
          inside(result.result) { case RegularRequestResult.Allow(blockContext, block) =>
            block.name should be(Block.Name("LDAP2"))
            assertBlockContext(loggedUser = Some(DirectlyLoggedUser(User.Id("kyle")))) {
              blockContext
            }
          }
        }
      }
    }
    "not be successful" when {
      "person from unreachable ldap is authenticated" in {
        val request = MockRequestContext.indices.withHeaders(basicAuthHeader("unreachableldapperson:somepass"))
        val result = acl.handleRegularRequest(request).runSyncUnsafe()
        result.history should have size 3
        inside(result.result) { case RegularRequestResult.ForbiddenByMismatched(causes) =>
          causes.toSortedSet should contain(ForbiddenCause.OperationNotAllowed)
        }
      }
    }
  }
}
