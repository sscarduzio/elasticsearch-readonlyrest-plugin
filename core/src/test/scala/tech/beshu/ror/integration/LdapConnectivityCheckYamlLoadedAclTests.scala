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

import com.dimafeng.testcontainers.ForAllTestContainer
import org.scalatest.Matchers.be
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.acl.AclHandlingResult.Result
import tech.beshu.ror.acl.blocks.Block
import tech.beshu.ror.acl.domain.{LoggedUser, User}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.LdapContainer
import tech.beshu.ror.utils.TestsUtils.basicAuthHeader
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Matchers._

class LdapConnectivityCheckYamlLoadedAclTests extends WordSpec with BaseYamlLoadedAclTest with ForAllTestContainer with Inside {

  override val container: LdapContainer = new LdapContainer("LDAP1", "/test_example.ldif")

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
       |      servers:
       |        - "ldap://${container.ldapHost}:${container.ldapPort}"
       |        - "ldap://localhost:666"                                # doesn't work
       |      ha: ROUND_ROBIN
       |      ssl_enabled: false                                        # default true
       |      ssl_trust_all_certs: true                                 # default false
       |      bind_dn: "cn=admin,dc=example,dc=com"                     # skip for anonymous bind
       |      bind_password: "password"                                 # skip for anonymous bind
       |      search_user_base_DN: "ou=People,dc=example,dc=com"
       |      user_id_attribute: "uid"                                  # default "uid
       |""".stripMargin

  "An LDAP connectivity check" should {
    "allow to core to start" when {
      "HA is enabled and one of LDAP hosts is unavailable" in {
        val request = MockRequestContext.default.copy(headers = Set(basicAuthHeader("cartman:user2")))
        val result = acl.handle(request).runSyncUnsafe()
        result.history should have size 1
        inside(result.handlingResult) { case Result.Allow(blockContext, block) =>
          block.name should be(Block.Name("LDAP test"))
          assertBlockContext(loggedUser = Some(LoggedUser(User.Id("cartman")))) {
            blockContext
          }
        }
      }
    }
  }
}
