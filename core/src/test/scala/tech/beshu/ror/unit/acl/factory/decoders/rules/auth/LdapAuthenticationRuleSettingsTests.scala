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
package tech.beshu.ror.unit.acl.factory.decoders.rules.auth

import org.scalatest.matchers.should.Matchers.*
import tech.beshu.ror.accesscontrol.blocks.rules.auth.LdapAuthenticationRule
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.unit.acl.factory.decoders.rules.BaseRuleSettingsDecoderTest
import tech.beshu.ror.utils.SingletonLdapContainers
import tech.beshu.ror.utils.TestsUtils.unsafeNes

class LdapAuthenticationRuleSettingsTests
  extends BaseRuleSettingsDecoderTest[LdapAuthenticationRule] {

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
              |    host: ${SingletonLdapContainers.ldap1.ldapHost}
              |    port: ${SingletonLdapContainers.ldap1.ldapPort}
              |    ssl_enabled: false
              |    search_user_base_DN: "ou=People,dc=example,dc=com"
              |""".stripMargin,
          assertion = rule => {
            assertLdapAuthNServiceLayerTypes(rule.settings.ldap)
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
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |""".stripMargin,
          assertion = rule => {
            assertLdapAuthNServiceLayerTypes(rule.settings.ldap, withRuleLevelCaching = true)
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
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |""".stripMargin,
          assertion = rule => {
            assertLdapAuthNServiceLayerTypes(rule.settings.ldap)
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
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
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
