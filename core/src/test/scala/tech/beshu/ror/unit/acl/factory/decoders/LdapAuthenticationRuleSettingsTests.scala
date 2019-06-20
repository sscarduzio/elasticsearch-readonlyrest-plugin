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
package tech.beshu.ror.unit.acl.factory.decoders

import com.dimafeng.testcontainers.{ForAllTestContainer, MultipleContainers}
import org.scalatest.Matchers._
import tech.beshu.ror.acl.blocks.definitions.ldap.{CacheableLdapAuthenticationServiceDecorator, LoggableLdapAuthenticationServiceDecorator}
import tech.beshu.ror.acl.blocks.rules.LdapAuthenticationRule
import tech.beshu.ror.acl.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.RawRorConfigBasedCoreFactory.AclCreationError.{RulesLevelCreationError, GeneralReadonlyrestSettingsError}
import tech.beshu.ror.utils.LdapContainer

class LdapAuthenticationRuleSettingsTests
  extends BaseRuleSettingsDecoderTest[LdapAuthenticationRule]
    with ForAllTestContainer{

  private val containerLdap1 = new LdapContainer("LDAP1", "/test_example.ldif")
  private val containerLdap2 = new LdapContainer("LDAP2", "/test_example.ldif")

  override val container: MultipleContainers = MultipleContainers(containerLdap1, containerLdap2)

  "An LdapAuthenticationRule" should {
    "be able to be loaded from config" when {
      "simple version of LDAP authentication rule is used" in {
        assertDecodingSuccess(
          yaml =
            s"""
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    ldap_authentication: "ldap1"
              |
              |  ldaps:
              |
              |  - name: ldap1
              |    host: ${containerLdap1.ldapHost}
              |    port: ${containerLdap1.ldapPort}
              |    ssl_enabled: false
              |    search_user_base_DN: "ou=People,dc=example,dc=com"
              |""".stripMargin,
          assertion = rule => {
            rule.settings.ldap shouldBe a [LoggableLdapAuthenticationServiceDecorator]
          }
        )
      }
      "authentication LDAP rule can have caching declared at rule level" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    ldap_authentication:
               |      name: ldap1
               |      cache_ttl: 10 sec
               |
               |  ldaps:
               |
               |  - name: ldap1
               |    host: ${containerLdap1.ldapHost}
               |    port: ${containerLdap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |""".stripMargin,
          assertion = rule => {
            rule.settings.ldap shouldBe a [CacheableLdapAuthenticationServiceDecorator]
          }
        )
      }
      "extended version of rule definition doesn't declare cache TTL" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    ldap_authentication:
               |      name: "ldap1"
               |
               |  ldaps:
               |
               |  - name: ldap1
               |    host: ${containerLdap1.ldapHost}
               |    port: ${containerLdap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |""".stripMargin,
          assertion = rule => {
            rule.settings.ldap shouldBe a [LoggableLdapAuthenticationServiceDecorator]
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "no LDAP service with given name is defined" in {
        assertDecodingFailure(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    ldap_authentication: "ldap2"
               |
               |  ldaps:
               |
               |  - name: ldap1
               |    host: ${containerLdap1.ldapHost}
               |    port: ${containerLdap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message("Cannot find LDAP service with name: ldap2")))
          }
        )
      }
    }
  }
}
