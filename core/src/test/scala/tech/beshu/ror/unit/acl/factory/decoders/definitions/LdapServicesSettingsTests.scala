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
package tech.beshu.ror.unit.acl.factory.decoders.definitions

import com.dimafeng.testcontainers.{ForAllTestContainer, MultipleContainers}
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers._
import tech.beshu.ror.accesscontrol.blocks.definitions.CircuitBreakerConfig
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.{UnboundidLdapAuthorizationService, UnboundidLdapConnectionPoolProvider, UserGroupsSearchFilterConfig}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.{ComposedLdapAuthService, _}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.LdapServicesDecoder
import tech.beshu.ror.utils.SingletonLdapContainers
import tech.beshu.ror.utils.TaskComonad.wait30SecTaskComonad
import tech.beshu.ror.utils.containers.LdapWithDnsContainer

import scala.concurrent.duration._
import scala.language.postfixOps
import org.joor.Reflect.on
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserGroupsSearchFilterConfig.UserGroupsSearchMode.{GroupNameAttribute, GroupSearchFilter, NestedGroupsConfig, UniqueMemberAttribute}

class LdapServicesSettingsTests private(ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider)
  extends BaseDecoderTest(LdapServicesDecoder.ldapServicesDefinitionsDecoder(ldapConnectionPoolProvider))
    with BeforeAndAfterAll
    with ForAllTestContainer {

  def this() = {
    this(new UnboundidLdapConnectionPoolProvider)
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    ldapConnectionPoolProvider.close()
  }

  private val ldapWithDnsContainer = new LdapWithDnsContainer("LDAP3", "test_example.ldif")

  override val container: MultipleContainers = MultipleContainers(
    SingletonLdapContainers.ldap1, SingletonLdapContainers.ldap1Backup, ldapWithDnsContainer
  )

  "An LdapService" should {
    "be able to be loaded from config" when {
      "one LDAP service is declared" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}                          # default 389
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
           """.stripMargin,
          assertion = { definitions =>
            definitions.items should have size 1
            val ldapService = definitions.items.head
            ldapService shouldBe a[CacheableLdapServiceDecorator]
            val ldapServiceUnderlying = getUnderlyingFieldFromCacheableLdapServiceDecorator(ldapService.asInstanceOf[CacheableLdapServiceDecorator])
            ldapServiceUnderlying shouldBe a[CircuitBreakerLdapServiceDecorator]
            ldapServiceUnderlying.asInstanceOf[CircuitBreakerLdapServiceDecorator].circuitBreakerConfig shouldBe CircuitBreakerConfig(Refined.unsafeApply(10), Refined.unsafeApply(10 seconds))
            ldapService.id should be(LdapService.Name("ldap1"))
          }
        )
      }
      "one LDAP service with circuit breaker is declared" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}                          # default 389
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
               |    circuit_breaker:
               |      max_retries: 3
               |      reset_duration: 2
           """.stripMargin,
          assertion = { definitions =>
            definitions.items should have size 1
            val ldapService = definitions.items.head
            ldapService shouldBe a[CacheableLdapServiceDecorator]
            val ldapServiceUnderlying = getUnderlyingFieldFromCacheableLdapServiceDecorator(ldapService.asInstanceOf[CacheableLdapServiceDecorator])
            ldapServiceUnderlying shouldBe a[CircuitBreakerLdapServiceDecorator]
            ldapServiceUnderlying.asInstanceOf[CircuitBreakerLdapServiceDecorator].circuitBreakerConfig shouldBe CircuitBreakerConfig(Refined.unsafeApply(3), Refined.unsafeApply(2 seconds))
            ldapService.id should be(LdapService.Name("ldap1"))
          }
        )
      }
      "two LDAP services are declared" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}                          # default 389
               |    ssl_enabled: false                                        # default true
               |    ssl_trust_all_certs: true                                 # default false
               |    bind_dn: "cn=admin,dc=example,dc=com"                     # skip for anonymous bind
               |    bind_password: "password"                                 # skip for anonymous bind
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
               |    user_id_attribute: "uid"                                  # default "uid"
               |    unique_member_attribute: "uniqueMember"                   # default "uniqueMember"
               |
               |  - name: ldap2
               |    host: ${SingletonLdapContainers.ldap1Backup.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1Backup.ldapPort}
               |    ssl_enabled: false                                        # default true
               |    ssl_trust_all_certs: true                                 # default false
               |    bind_dn: "cn=admin,dc=example,dc=com"                     # skip for anonymous bind
               |    bind_password: "password"                                 # skip for anonymous bind
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    user_id_attribute: "uid"                                  # default "uid"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
               |    unique_member_attribute: "uniqueMember"                   # default "uniqueMember"
           """.stripMargin,
          assertion = { definitions =>
            definitions.items should have size 2
            val ldap1Service = definitions.items.head
            ldap1Service.id should be(LdapService.Name("ldap1"))
            ldap1Service shouldBe a[LdapAuthService]
            getLdapAuthorizationGroupsSearchFilterConfig(ldap1Service) should be(None)

            val ldap2Service = definitions.items(1)
            ldap2Service.id should be(LdapService.Name("ldap2"))
            ldap2Service shouldBe a[LdapAuthService]
            getLdapAuthorizationGroupsSearchFilterConfig(ldap2Service) should be(None)
          }
        )
      }
      "LDAP authentication service definition is declared only using required fields" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapSSLPort}
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    ssl_trust_all_certs: true  #this is actually not required (but we use openLDAP default cert to test)
           """.stripMargin,
          assertion = { definitions =>
            definitions.items should have size 1
            val ldapService = definitions.items.head
            ldapService.id should be(LdapService.Name("ldap1"))
            ldapService shouldBe a[LdapAuthenticationService]
          }
        )
      }
      "LDAP authorization service definition is declared only using required fields" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapSSLPort}
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=People,dc=example,dc=com"
               |    ssl_trust_all_certs: true  #this is actually not required (but we use openLDAP default cert to test)
           """.stripMargin,
          assertion = { definitions =>
            definitions.items should have size 1
            val ldapService = definitions.items.head
            ldapService.id should be(LdapService.Name("ldap1"))
            ldapService shouldBe a[LdapAuthenticationService]
          }
        )
      }
      "Unreachable host is configured, but connection check on startup is disabled" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    host: 192.168.123.123
               |    port: 234
               |    ignore_ldap_connectivity_problems: true
               |    connection_timeout: 500 ms
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    ssl_trust_all_certs: true  #this is actually not required (but we use openLDAP default cert to test)
           """.stripMargin,
          assertion = { definitions =>
            definitions.items should have size 1
            val ldapService = definitions.items.head
            ldapService.id should be(LdapService.Name("ldap1"))
            ldapService shouldBe a[LdapAuthenticationService]
          }
        )
      }
      "Unknown host is configured, but connection check on startup is disabled" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    host: dummy-host
               |    port: 234
               |    ignore_ldap_connectivity_problems: true
               |    connection_timeout: 500 ms
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    ssl_trust_all_certs: true  #this is actually not required (but we use openLDAP default cert to test)
           """.stripMargin,
          assertion = { definitions =>
            definitions.items should have size 1
            val ldapService = definitions.items.head
            ldapService.id should be(LdapService.Name("ldap1"))
            ldapService shouldBe a[LdapAuthenticationService]
          }
        )
      }
      "two LDAP hosts are defined" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    hosts:
               |    - "ldap://${SingletonLdapContainers.ldap1Backup.ldapHost}:${SingletonLdapContainers.ldap1Backup.ldapPort}"
               |    - "ldap://${SingletonLdapContainers.ldap1.ldapHost}:${SingletonLdapContainers.ldap1.ldapPort}"
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
           """.stripMargin,
          assertion = { definitions =>
            definitions.items should have size 1
            val ldapService = definitions.items.head
            ldapService shouldBe a[LdapAuthService]
            ldapService.id should be(LdapService.Name("ldap1"))
          }
        )
      }
      "server discovery is enabled" in {
        val ignoreLdapConnectivityProblems = "true" // required to test settings decoding locally
        assertDecodingSuccess(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    server_discovery: true
               |    ignore_ldap_connectivity_problems: $ignoreLdapConnectivityProblems
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
           """.stripMargin,
          assertion = { definitions =>
            definitions.items should have size 1
            val ldapService = definitions.items.head
            ldapService shouldBe a[LdapAuthService]
            ldapService.id should be(LdapService.Name("ldap1"))
          }
        )
      }
      "server discovery is enabled with custom dns and custom ttl" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    server_discovery:
               |        dns_url: "dns://localhost:${ldapWithDnsContainer.dnsPort}"
               |        ttl: "3 hours"
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
           """.stripMargin,
          assertion = { definitions =>
            definitions.items should have size 1
            val ldapService = definitions.items.head
            ldapService shouldBe a[LdapAuthService]
            ldapService.id should be(LdapService.Name("ldap1"))
          }
        )
      }
      "ROUND_ROBIN HA method is defined" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    hosts:
               |    - "ldap://${SingletonLdapContainers.ldap1Backup.ldapHost}:${SingletonLdapContainers.ldap1Backup.ldapPort}"
               |    - "ldap://${SingletonLdapContainers.ldap1.ldapHost}:${SingletonLdapContainers.ldap1.ldapPort}"
               |    ha: ROUND_ROBIN
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
           """.stripMargin,
          assertion = { definitions =>
            definitions.items should have size 1
            val ldapService = definitions.items.head
            ldapService shouldBe a[LdapAuthService]
            ldapService.id should be(LdapService.Name("ldap1"))
          }
        )
      }
      "groups from user attribute mode is used" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    hosts:
               |    - "ldap://${SingletonLdapContainers.ldap1Backup.ldapHost}:${SingletonLdapContainers.ldap1Backup.ldapPort}"
               |    - "ldap://${SingletonLdapContainers.ldap1.ldapHost}:${SingletonLdapContainers.ldap1.ldapPort}"
               |    ha: ROUND_ROBIN
               |    ssl_enabled: false                                        # default true
               |    ssl_trust_all_certs: true                                 # default false
               |    bind_dn: "cn=admin,dc=example,dc=com"                     # skip for anonymous bind
               |    bind_password: "password"                                 # skip for anonymous bind
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
               |    groups_from_user: true
           """.stripMargin,
          assertion = { definitions =>
            definitions.items should have size 1
            val ldapService = definitions.items.head
            ldapService shouldBe a[LdapAuthService]
            ldapService.id should be(LdapService.Name("ldap1"))
          }
        )
      }
      "nested groups are enabled" when {
        "used with `groups_from_user: false`" in {
          assertDecodingSuccess(
            yaml =
              s"""
                 |  ldaps:
                 |  - name: ldap1
                 |    host: ${SingletonLdapContainers.ldap1.ldapHost}
                 |    port: ${SingletonLdapContainers.ldap1.ldapPort}           # default 389
                 |    ssl_enabled: false                                        # default true
                 |    ssl_trust_all_certs: true                                 # default false
                 |    bind_dn: "cn=admin,dc=example,dc=com"                     # skip for anonymous bind
                 |    bind_password: "password"                                 # skip for anonymous bind
                 |    search_user_base_DN: "ou=People,dc=example,dc=com"
                 |    user_id_attribute: "uid"                                  # default "uid"
                 |
                 |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
                 |    nested_groups_depth: 5
           """.stripMargin,
            assertion = { definitions =>
              definitions.items should have size 1
              val ldapService = definitions.items.head
              ldapService shouldBe a[CircuitBreakerLdapServiceDecorator]
              ldapService.asInstanceOf[CircuitBreakerLdapServiceDecorator].circuitBreakerConfig shouldBe CircuitBreakerConfig(Refined.unsafeApply(10), Refined.unsafeApply(10 seconds))
              ldapService.id should be(LdapService.Name("ldap1"))

              getLdapAuthorizationGroupsSearchFilterConfig(ldapService) should be(Some(NestedGroupsConfig(
                nestedLevels = 5,
                Dn("ou=Groups,dc=example,dc=com"),
                GroupSearchFilter("(objectClass=*)"),
                UniqueMemberAttribute("uniqueMember"),
                GroupNameAttribute("cn")
              )))
            }
          )
        }
        "used with `groups_from_user: true`" in {
          assertDecodingSuccess(
            yaml =
              s"""
                 |  ldaps:
                 |  - name: ldap1
                 |    host: ${SingletonLdapContainers.ldap1.ldapHost}
                 |    port: ${SingletonLdapContainers.ldap1.ldapPort}           # default 389
                 |    ssl_enabled: false                                        # default true
                 |    ssl_trust_all_certs: true                                 # default false
                 |    bind_dn: "cn=admin,dc=example,dc=com"                     # skip for anonymous bind
                 |    bind_password: "password"                                 # skip for anonymous bind
                 |    search_user_base_DN: "ou=People,dc=example,dc=com"
                 |    user_id_attribute: "uid"                                  # default "uid"
                 |
                 |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
                 |    groups_from_user: true
                 |    nested_groups_depth: 5
           """.stripMargin,
            assertion = { definitions =>
              definitions.items should have size 1
              val ldapService = definitions.items.head
              ldapService shouldBe a[CircuitBreakerLdapServiceDecorator]
              ldapService.asInstanceOf[CircuitBreakerLdapServiceDecorator].circuitBreakerConfig shouldBe CircuitBreakerConfig(Refined.unsafeApply(10), Refined.unsafeApply(10 seconds))
              ldapService.id should be(LdapService.Name("ldap1"))

              getLdapAuthorizationGroupsSearchFilterConfig(ldapService) should be(Some(NestedGroupsConfig(
                nestedLevels = 5,
                Dn("ou=Groups,dc=example,dc=com"),
                GroupSearchFilter("(objectClass=*)"),
                UniqueMemberAttribute("uniqueMember"),
                GroupNameAttribute("cn")
              )))
            }
          )
        }
      }
    }
    "not be able to be loaded from config" when {
      "circuit breaker config is malformed" in {
        assertDecodingFailure(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapSSLPort}
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    ssl_trust_all_certs: true  #this is actually not required (but we use openLDAP default cert to test)
               |    circuit_breaker:
               |      max_retries: 1
           """.stripMargin,
          assertion = { error =>
            error should be(CoreCreationError.DefinitionsLevelCreationError(Message("At least proper values for max_retries and reset_duration are required for circuit breaker configuration")))
          }
        )
      }
      "no LDAP service is defined" in {
        assertDecodingFailure(
          yaml =
            s"""
               |  ldaps:
           """.stripMargin,
          assertion = { error =>
            error should be(CoreCreationError.DefinitionsLevelCreationError(Message("ldaps declared, but no definition found")))
          }
        )
      }
      "LDAP service doesn't have a name" in {
        assertDecodingFailure(
          yaml =
            s"""
               |  ldaps:
               |  - host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
           """.stripMargin,
          assertion = { error =>
            inside(error) { case CoreCreationError.DefinitionsLevelCreationError(MalformedValue(message)) =>
              message should include(s"""host: "${SingletonLdapContainers.ldap1.ldapHost}"""")
              message should include(s"""port: ${SingletonLdapContainers.ldap1.ldapPort}""")
              message should include(s"""search_user_base_DN: "ou=People,dc=example,dc=com"""")
              message should include(s"""search_groups_base_DN: "ou=Groups,dc=example,dc=com"""")
            }
          }
        )
      }
      "names of LDAP services are not unique" in {
        assertDecodingFailure(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    ssl_trust_all_certs: true
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
               |
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
           """.stripMargin,
          assertion = { error =>
            error should be(CoreCreationError.DefinitionsLevelCreationError(Message("ldaps definitions must have unique identifiers. Duplicates: ldap1")))
          }
        )
      }
      "LDAP service cache TTL value is malformed" in {
        assertDecodingFailure(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
               |    cache_ttl_in_sec: infinity
           """.stripMargin,
          assertion = { error =>
            error should be(CoreCreationError.ValueLevelCreationError(Message("Cannot convert value '\"infinity\"' to duration")))
          }
        )
      }
      "LDAP service cache TTL value is negative" in {
        assertDecodingFailure(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
               |    cache_ttl_in_sec: -10
           """.stripMargin,
          assertion = { error =>
            error should be(CoreCreationError.ValueLevelCreationError(Message("Only positive values allowed. Found: -10 seconds")))
          }
        )
      }
      "only DN field of custom bind request user is defined" in {
        assertDecodingFailure(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    bind_dn: "cn=admin,dc=example,dc=com"
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
           """.stripMargin,
          assertion = { error =>
            error should be(CoreCreationError.DefinitionsLevelCreationError(Message("'bind_dn' & 'bind_password' should be both present or both absent")))
          }
        )
      }
      "only password field of custom bind request user is defined" in {
        assertDecodingFailure(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    bind_password: "pass"
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
           """.stripMargin,
          assertion = { error =>
            error should be(CoreCreationError.DefinitionsLevelCreationError(Message("'bind_dn' & 'bind_password' should be both present or both absent")))
          }
        )
      }
      "no LDAP host is defined" in {
        assertDecodingFailure(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
           """.stripMargin,
          assertion = { error =>
            error should be(CoreCreationError.DefinitionsLevelCreationError(Message("Server information missing: use 'host' and 'port', 'servers'/'hosts' or 'service_discovery' option.")))
          }
        )
      }
      "single host settings and multi hosts settings are used in the same time" in {
        assertDecodingFailure(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    servers:
               |    - "ldap://${SingletonLdapContainers.ldap1Backup.ldapHost}:${SingletonLdapContainers.ldap1Backup.ldapPort}"
               |    - "ldap://${SingletonLdapContainers.ldap1.ldapHost}:${SingletonLdapContainers.ldap1.ldapPort}"
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
           """.stripMargin,
          assertion = { error =>
            error should be(CoreCreationError.DefinitionsLevelCreationError(Message("Cannot accept multiple server configurations settings (host,port) or (servers/hosts) or (service_discovery) at the same time.")))
          }
        )
      }
      "single host settings and server discovery settings are used in the same time" in {
        assertDecodingFailure(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    server_discovery: true
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
           """.stripMargin,
          assertion = { error =>
            error should be(CoreCreationError.DefinitionsLevelCreationError(Message("Cannot accept multiple server configurations settings (host,port) or (servers/hosts) or (service_discovery) at the same time.")))
          }
        )
      }
      "HA method is used for single LDAP host settings" in {
        assertDecodingFailure(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ha: ROUND_ROBIN
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
           """.stripMargin,
          assertion = { error =>
            error should be(CoreCreationError.DefinitionsLevelCreationError(Message("Please specify more than one LDAP server using 'servers'/'hosts' to use HA")))
          }
        )
      }
      "unknown HA method is defined" in {
        assertDecodingFailure(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    servers:
               |    - "ldap://${SingletonLdapContainers.ldap1Backup.ldapHost}:${SingletonLdapContainers.ldap1Backup.ldapPort}"
               |    - "ldap://${SingletonLdapContainers.ldap1.ldapHost}:${SingletonLdapContainers.ldap1.ldapPort}"
               |    ha: RANDOM
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
           """.stripMargin,
          assertion = { error =>
            error should be(CoreCreationError.DefinitionsLevelCreationError(Message("Unknown HA method 'RANDOM'")))
          }
        )
      }
      "one of LDAP services is unavailable (invalid port)" in {
        assertDecodingFailure(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    host: "localhost"
               |    port: 12345
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
           """.stripMargin,
          assertion = { error =>
            error should be(CoreCreationError.DefinitionsLevelCreationError(Message("There was a problem with LDAP connection to: ldaps://localhost:12345")))
          }
        )
      }
      "one of LDAP services is unavailable (invalid host)" in {
        assertDecodingFailure(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    host: "dummy-host"
               |    port: 12345
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
             """.stripMargin,
          assertion = { error =>
            error should be(CoreCreationError.DefinitionsLevelCreationError(Message("There was a problem with LDAP connection to: ldaps://dummy-host:12345")))
          }
        )
      }
      "some of LDAP services are unavailable" in {
        assertDecodingFailure(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    hosts:
               |      - ldaps://ssl-ldap2.foo.com:836
               |      - ldaps://ssl-ldap3.foo.com:836
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
               |    connection_timeout_in_sec: 2
            """.stripMargin,
          assertion = { error =>
            error should be(CoreCreationError.DefinitionsLevelCreationError(Message("There was a problem with LDAP connection to: ldaps://ssl-ldap2.foo.com:836,ldaps://ssl-ldap3.foo.com:836")))
          }
        )
      }
      "malformed ldap host syntax" in {
        assertDecodingFailure(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    host: "ldaps://ssl-ldap2.foo.com:836"
               |    port: 12345
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
             """.stripMargin,
          assertion = { error =>
            error should be(CoreCreationError.DefinitionsLevelCreationError(Message("Server information missing: use 'host' and 'port', 'servers'/'hosts' or 'service_discovery' option.")))
          }
        )
      }
      "connection pool size is malformed" in {
        assertDecodingFailure(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
               |    connection_pool_size: -10
           """.stripMargin,
          assertion = { error =>
            error should be(CoreCreationError.DefinitionsLevelCreationError(Message("Only positive values allowed. Found: -10")))
          }
        )
      }
      "connection timeout is malformed" in {
        assertDecodingFailure(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
               |    connection_timeout_in_sec: -10
           """.stripMargin,
          assertion = { error =>
            error should be(CoreCreationError.DefinitionsLevelCreationError(Message("Only positive values allowed. Found: -10 seconds")))
          }
        )
      }
      "request timeout is malformed" in {
        assertDecodingFailure(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
               |    request_timeout_in_sec: -10
           """.stripMargin,
          assertion = { error =>
            error should be(CoreCreationError.DefinitionsLevelCreationError(Message("Only positive values allowed. Found: -10 seconds")))
          }
        )
      }
      "LDAP hosts have different schemas" in {
        assertDecodingFailure(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    hosts:
               |    - "ldaps://${SingletonLdapContainers.ldap1.ldapHost}:${SingletonLdapContainers.ldap1.ldapPort}"
               |    - "ldap://${SingletonLdapContainers.ldap1Backup.ldapHost}:${SingletonLdapContainers.ldap1Backup.ldapPort}"
               |    ssl_trust_all_certs: true
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
           """.stripMargin,
          assertion = { error =>
            error should be(CoreCreationError.DefinitionsLevelCreationError(Message("The list of LDAP servers should be either all 'ldaps://' or all 'ldap://")))
          }
        )
      }
    }
  }

  private def getUnderlyingFieldFromCacheableLdapServiceDecorator(cacheableLdapServiceDecorator: CacheableLdapServiceDecorator) = {
    on(cacheableLdapServiceDecorator).get[LdapService]("underlying")
  }

  private def getLdapAuthorizationGroupsSearchFilterConfig(ldapService: LdapService) = {
    val composedLdapAuthService = on(ldapService).get[ComposedLdapAuthService]("underlying")
    val ldapAuthorizationService = on(composedLdapAuthService).get[UnboundidLdapAuthorizationService]("ldapAuthorizationService")
    val groupsSearchFilterConfig = on(ldapAuthorizationService).get[UserGroupsSearchFilterConfig]("groupsSearchFilter")
    groupsSearchFilterConfig.nestedGroupsConfig
  }
}
