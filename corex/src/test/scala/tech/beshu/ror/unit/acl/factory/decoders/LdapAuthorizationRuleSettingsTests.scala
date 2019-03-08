package tech.beshu.ror.unit.acl.factory.decoders

import cats.data._
import com.dimafeng.testcontainers.{ForAllTestContainer, MultipleContainers}
import org.scalatest.Matchers._
import tech.beshu.ror.acl.blocks.definitions.ldap._
import tech.beshu.ror.acl.blocks.rules.LdapAuthorizationRule
import tech.beshu.ror.acl.domain.Group
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.utils.LdapContainer
import tech.beshu.ror.utils.TestsUtils._

class LdapAuthorizationRuleSettingsTests
  extends BaseRuleSettingsDecoderTest[LdapAuthorizationRule]
    with ForAllTestContainer{

  private val containerLdap1 = new LdapContainer("LDAP1", "/test_example.ldif")
  private val containerLdap2 = new LdapContainer("LDAP2", "/test_example.ldif")

  override val container: MultipleContainers = MultipleContainers(containerLdap1, containerLdap2)

  "A LdapAuthorizationRule" should {
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
               |    host: ${containerLdap1.ldapHost}
               |    port: ${containerLdap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=People,dc=example,dc=com"
               |""".stripMargin,
          assertion = rule => {
            rule.settings.ldap shouldBe a [LoggableLdapServiceDecorator]
            rule.settings.permittedGroups should be (NonEmptySet.one(Group("group3".nonempty)))
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
               |    host: ${containerLdap1.ldapHost}
               |    port: ${containerLdap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=People,dc=example,dc=com"
               |""".stripMargin,
          assertion = rule => {
            rule.settings.ldap shouldBe a [CacheableLdapAuthorizationServiceDecorator]
            rule.settings.permittedGroups should be (NonEmptySet.one(Group("group3".nonempty)))
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
               |    host: ${containerLdap1.ldapHost}
               |    port: ${containerLdap1.ldapPort}
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
               |    host: ${containerLdap1.ldapHost}
               |    port: ${containerLdap1.ldapPort}
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
               |    host: ${containerLdap1.ldapHost}
               |    port: ${containerLdap1.ldapPort}
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
               |    host: ${containerLdap1.ldapHost}
               |    port: ${containerLdap1.ldapPort}
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
