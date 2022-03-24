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

import eu.timepit.refined.auto._
import org.scalatest.matchers.should.Matchers._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap._
import tech.beshu.ror.accesscontrol.blocks.rules.LdapAuthorizationRule
import tech.beshu.ror.accesscontrol.domain.Group
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.utils.SingletonLdapContainers
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class LdapAuthorizationRuleSettingsTests
  extends BaseRuleSettingsDecoderTest[LdapAuthorizationRule] {

  "An LdapAuthorizationRule" should {
    "be able to be loaded from config" when {
      "there is LDAP service with given name and groups are defined" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
               |    ldap_authorization:
               |      name: "ldap1"
               |      groups: ["group3"]
               |
               |  ldaps:
               |
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=People,dc=example,dc=com"
               |""".stripMargin,
          assertion = rule => {
            rule.settings.ldap shouldBe a [LoggableLdapAuthorizationServiceDecorator]
            rule.settings.ldap.asInstanceOf[LoggableLdapAuthorizationServiceDecorator].underlying shouldBe a [CircuitBreakerLdapServiceDecorator]
            rule.settings.permittedGroups should be (UniqueNonEmptyList.of(Group("group3")))
          }
        )
      }
      "authorization LDAP rule can have caching declared at rule level" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
               |    ldap_authorization:
               |      name: "ldap1"
               |      groups: ["group3"]
               |      cache_ttl: 10 sec
               |
               |  ldaps:
               |
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=People,dc=example,dc=com"
               |""".stripMargin,
          assertion = rule => {
            rule.settings.ldap shouldBe a [LoggableLdapAuthorizationServiceDecorator]
            rule.settings.ldap.asInstanceOf[LoggableLdapAuthorizationServiceDecorator].underlying shouldBe a [CacheableLdapAuthorizationServiceDecorator]
            rule.settings.permittedGroups should be (UniqueNonEmptyList.of(Group("group3")))
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
               |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
               |    ldap_authorization:
               |      name: "ldap2"
               |      groups: ["group3"]
               |      cache_ttl_in_seconds: 10
               |
               |  ldaps:
               |
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=People,dc=example,dc=com"
               |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message("Cannot find LDAP service with name: ldap2")))
          }
        )
      }
      "groups are not defined" in {
        assertDecodingFailure(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
               |    ldap_authorization:
               |      name: "ldap1"
               |
               |  ldaps:
               |
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=People,dc=example,dc=com"
               |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue(
              """ldap_authorization:
                |  name: "ldap1"
                |""".stripMargin)))
          }
        )
      }
      "groups set is empty" in {
        assertDecodingFailure(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
               |    ldap_authorization:
               |      name: "ldap1"
               |      groups: []
               |      cache_ttl_in_seconds: 10
               |
               |  ldaps:
               |
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=People,dc=example,dc=com"
               |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message("Non empty list of groups are required")))
          }
        )
      }
      "LDAP service can only handle authentication, not authorization" in {
        assertDecodingFailure(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
               |    ldap_authorization:
               |      name: "ldap1"
               |      groups: ["group1"]
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
            errors.head should be(RulesLevelCreationError(Message("Service: ldap1 cannot be used in 'ldap_authorization' rule")))
          }
        )
      }
    }
  }
}
