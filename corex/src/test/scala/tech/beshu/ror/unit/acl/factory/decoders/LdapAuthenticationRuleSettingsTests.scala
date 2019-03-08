package tech.beshu.ror.unit.acl.factory.decoders

import com.dimafeng.testcontainers.{ForAllTestContainer, MultipleContainers}
import org.scalatest.Matchers._
import tech.beshu.ror.acl.blocks.definitions.ldap.{CacheableLdapAuthenticationServiceDecorator, LoggableLdapAuthenticationServiceDecorator}
import tech.beshu.ror.acl.blocks.rules.LdapAuthenticationRule
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.{RulesLevelCreationError, UnparsableYamlContent}
import tech.beshu.ror.utils.LdapContainer

class LdapAuthenticationRuleSettingsTests
  extends BaseRuleSettingsDecoderTest[LdapAuthenticationRule]
    with ForAllTestContainer{

  private val containerLdap1 = new LdapContainer("LDAP1", "/test_example.ldif")
  private val containerLdap2 = new LdapContainer("LDAP2", "/test_example.ldif")

  override val container: MultipleContainers = MultipleContainers(containerLdap1, containerLdap2)

  "A LdapAuthenticationRule" should {
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
      "extended version of rule definition doesn't declare cache TTL" in {
        assertDecodingFailure(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    ldap_authentication:
               |      name: "ldap2"
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
            errors.head should be(RulesLevelCreationError(MalformedValue(
              """ldap_authentication:
                |  name: "ldap2"
                |""".stripMargin)))
          }
        )
      }
      "extended version of rule definition cannot be mixes with simple one" in {
        assertDecodingFailure(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    ldap_authentication: "ldap1"
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
          assertion = errors => {
            errors should have size 1
            inside(errors.head) { case UnparsableYamlContent(Message(_)) => }
          }
        )
      }
    }
  }
}
