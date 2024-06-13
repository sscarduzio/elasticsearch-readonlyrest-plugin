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

import eu.timepit.refined.auto._
import com.dimafeng.testcontainers.ForAllTestContainer
import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterAll, Inside}
import tech.beshu.ror.accesscontrol.AccessControl.RegularRequestResult
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.User
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils.basicAuthHeader
import tech.beshu.ror.utils.containers.LdapWithDnsContainer
import tech.beshu.ror.utils.TestsUtils.unsafeNes

class LdapServerDiscoveryCheckYamlLoadedAccessControlTests
  extends AnyWordSpec
    with BaseYamlLoadedAccessControlTest
    with BeforeAndAfterAll
    with ForAllTestContainer
    with Inside {

  override val container: LdapWithDnsContainer = new LdapWithDnsContainer("LDAP1", "test_example.ldif")

  override protected def configYaml: String =
    s"""readonlyrest:
       |
       |  access_control_rules:
       |
       |    - name: "LDAP test"
       |      ldap_authentication: "ldap1"
       |
       |  ldaps:
       |    - name: ldap1
       |      server_discovery:
       |        dns_url: "dns://localhost:${container.dnsPort}"
       |      ha: ROUND_ROBIN
       |      ssl_enabled: false                                        # default true
       |      ssl_trust_all_certs: true                                 # default false
       |      bind_dn: "cn=admin,dc=example,dc=com"                     # skip for anonymous bind
       |      bind_password: "password"                                 # skip for anonymous bind
       |      users:
       |        search_user_base_DN: "ou=People,dc=example,dc=com"
       |        user_id_attribute: "uid"                                # default "uid
       |
       |""".stripMargin

  override protected val ldapConnectionPoolProvider = new UnboundidLdapConnectionPoolProvider

  override protected def afterAll(): Unit = {
    super.afterAll()
    ldapConnectionPoolProvider.close().runSyncUnsafe()
  }

  "An LDAP connectivity check" should {
    "allow core to start" when {
      "server discovery is used and DNS responds with proper address" in {
        val request = MockRequestContext.indices.copy(headers = Set(basicAuthHeader("cartman:user2")))
        val result = acl.handleRegularRequest(request).runSyncUnsafe()
        result.history should have size 1
        inside(result.result) { case RegularRequestResult.Allow(blockContext, block) =>
          block.name should be(Block.Name("LDAP test"))
          assertBlockContext(loggedUser = Some(DirectlyLoggedUser(User.Id("cartman")))) {
            blockContext
          }
        }
      }
    }
  }
}
