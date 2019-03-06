package tech.beshu.ror.unit.acl.factory.decoders.definitions

import com.dimafeng.testcontainers.{ForAllTestContainer, MultipleContainers}
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Matchers._
import tech.beshu.ror.acl.blocks.definitions.ldap.{LdapAuthService, LdapAuthenticationService, LdapService}
import tech.beshu.ror.acl.factory.decoders.definitions.LdapServicesDecoder
import tech.beshu.ror.utils.LdapContainer
import tech.beshu.ror.utils.TestsUtils.StringOps

import scala.concurrent.duration._
import scala.language.postfixOps

class LdapServicesSettingsTests
  extends BaseDecoderTest(LdapServicesDecoder.ldapServicesDefinitionsDecoder)
    with ForAllTestContainer {

  private val containerLdap1 = new LdapContainer("LDAP1", "/test_example.ldif")
  private val containerLdap2 = new LdapContainer("LDAP2", "/test_example.ldif")

  override val container: MultipleContainers = MultipleContainers(containerLdap1, containerLdap2)

  "A LdapService" should {
    "be able to be loaded from config" when {
      "one LDAP service is declared" in {
        val definitions = forceDecode {
          s"""
             |  ldaps:
             |  - name: ldap1
             |    host: ${containerLdap1.ldapHost}
             |    port: ${containerLdap1.ldapPort}                          # default 389
             |    ssl_enabled: false                                        # default true
             |    ssl_trust_all_certs: true                                 # default false
             |    bind_dn: "cn=admin,dc=example,dc=com"                     # skip for anonymous bind
             |    bind_password: "password"                                 # skip for anonymous bind
             |    search_user_base_DN: "ou=People,dc=example,dc=com"
             |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
             |    user_id_attribute: "uid"                                  # default "uid"
             |    unique_member_attribute: "uniqueMember"                   # default "uniqueMember"
             |    connection_pool_size: 10                                  # default 30
             |    connection_timeout_in_sec: 10                             # default 1
             |    request_timeout_in_sec: 10                                # default 1
             |    cache_ttl_in_sec: 60                                      # default 0 - cache disabled
           """.stripMargin
        }
        definitions.items should have size 1
        val ldapService = definitions.items.head
        ldapService shouldBe a[LdapAuthService]
        ldapService.id should be(LdapService.Name("ldap1".nonempty))
      }
      "two LDAP services are declared" in {
        val definitions = forceDecode {
          s"""
             |  ldaps:
             |  - name: ldap1
             |    host: ${containerLdap1.ldapHost}
             |    port: ${containerLdap1.ldapPort}                          # default 389
             |    ssl_enabled: false                                        # default true
             |    ssl_trust_all_certs: true                                 # default false
             |    bind_dn: "cn=admin,dc=example,dc=com"                     # skip for anonymous bind
             |    bind_password: "password"                                 # skip for anonymous bind
             |    search_user_base_DN: "ou=People,dc=example,dc=com"
             |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
             |    user_id_attribute: "uid"                                  # default "uid"
             |    unique_member_attribute: "uniqueMember"                   # default "uniqueMember"
             |    connection_pool_size: 10                                  # default 30
             |    connection_timeout_in_sec: 10                             # default 1
             |    request_timeout_in_sec: 10                                # default 1
             |    cache_ttl_in_sec: 60                                      # default 0 - cache disabled
             |
             |  - name: ldap2
             |    host: ${containerLdap2.ldapHost}
             |    port: ${containerLdap2.ldapPort}                          # default 389
             |    ssl_enabled: false                                        # default true
             |    ssl_trust_all_certs: true                                 # default false
             |    bind_dn: "cn=admin,dc=example,dc=com"                     # skip for anonymous bind
             |    bind_password: "password"                                 # skip for anonymous bind
             |    search_user_base_DN: "ou=People,dc=example,dc=com"
             |    user_id_attribute: "uid"                                  # default "uid"
             |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
             |    unique_member_attribute: "uniqueMember"                   # default "uniqueMember"
             |    connection_pool_size: 10                                  # default 30
             |    connection_timeout_in_sec: 10                             # default 1
             |    request_timeout_in_sec: 10                                # default 1
             |    cache_ttl_in_sec: 60                                      # default 0 - cache disabled
           """.stripMargin
        }
        definitions.items should have size 2
        val ldap1Service = definitions.items.head
        ldap1Service.id should be(LdapService.Name("ldap1".nonempty))
        ldap1Service shouldBe a[LdapAuthService]

        val ldap2Service = definitions.items(1)
        ldap2Service.id should be(LdapService.Name("ldap2".nonempty))
        ldap2Service shouldBe a[LdapAuthService]
      }
      "LDAP authentication service definition is declared only using required fields" in {
        val definitions = forceDecode {
          s"""
             |  ldaps:
             |  - name: ldap1
             |    host: ${containerLdap1.ldapHost}
             |    port: ${containerLdap1.ldapPort}                          # default 389
             |    search_user_base_DN: "ou=People,dc=example,dc=com"
           """.stripMargin
        }
        definitions.items should have size 1
        val ldapService = definitions.items.head
        ldapService.id should be(LdapService.Name("ldap1".nonempty))
        ldapService shouldBe a[LdapAuthenticationService]
      }
      "LDAP authorization service definition is declared only using required fields" in {
        val definitions = forceDecode {
          s"""
             |  ldaps:
             |  - name: ldap1
             |    host: ${containerLdap1.ldapHost}
             |    port: ${containerLdap1.ldapPort}                          # default 389
             |    search_user_base_DN: "ou=People,dc=example,dc=com"
             |    search_groups_base_DN: "ou=People,dc=example,dc=com"
           """.stripMargin
        }
        definitions.items should have size 1
        val ldapService = definitions.items.head
        ldapService.id should be(LdapService.Name("ldap1".nonempty))
        ldapService shouldBe a[LdapAuthenticationService]
      }
      "two LDAP hosts are defined" in {
        val definitions = forceDecode {
          s"""
             |  ldaps:
             |  - name: ldap1
             |    hosts:
             |    - "ldap://${containerLdap2.ldapHost}:${containerLdap2.ldapPort}"
             |    - "ldap://${containerLdap1.ldapHost}:${containerLdap1.ldapPort}"
             |    ssl_enabled: false                                        # default true
             |    ssl_trust_all_certs: true                                 # default false
             |    bind_dn: "cn=admin,dc=example,dc=com"                     # skip for anonymous bind
             |    bind_password: "password"                                 # skip for anonymous bind
             |    search_user_base_DN: "ou=People,dc=example,dc=com"
             |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
             |    user_id_attribute: "uid"                                  # default "uid"
             |    unique_member_attribute: "uniqueMember"                   # default "uniqueMember"
             |    connection_pool_size: 10                                  # default 30
             |    connection_timeout_in_sec: 10                             # default 1
             |    request_timeout_in_sec: 10                                # default 1
             |    cache_ttl_in_sec: 60                                      # default 0 - cache disabled
           """.stripMargin
        }
        definitions.items should have size 1
        val ldapService = definitions.items.head
        ldapService shouldBe a[LdapAuthService]
        ldapService.id should be(LdapService.Name("ldap1".nonempty))
      }
      "ROUND_ROBIN HA method is defined" in {

      }
      "custom pool size is configured" in {

      }
      "custom connection timeout is configured" in {

      }
      "custom request timeout is configured" in {

      }
      "default group search mode is used" in {

      }
      "groups from user attribute mode is used" in {

      }
    }
    "not be able to be loaded from config" when {
      "no LDAP service is defined" in {

      }
      "no LDAP service with given name is defined" in {

      }
      "LDAP service doesn't have a name" in {

      }
      "names of LDAP services are not unique" in {

      }
      "LDAP service TTL value is malformed" in {

      }
      "LDAP service TTL value is negative" in {

      }
      "only DN field of custom bind request user is defined" in {

      }
      "only password field of custom bind request user is defined" in {

      }
      "no LDAP host is defined" in {

      }
      "single host settings and multi hosts settings are used in the same time" in {

      }
      "HA method is used for single LDAP host settings" in {

      }
      "unknown HA method is defined" in {

      }
      "host is malformed" in {

      }
      "one of host is malformed" in {

      }
      "connection pool size is malformed" in {

      }
      "one of LDAP services are unavailable" in {

      }
      "connection timeout is malformed" in {

      }
      "request timeout is malformed" in {

      }
      "default group search mode is malformed" in {

      }
      "groups from user attribute mode is used" in {

      }
      "both modes are configured" in {

      }
    }
  }

  private def forceDecode(yaml: String) = {
    decode(yaml).runSyncUnsafe(timeout = 10 second)
  }
}
