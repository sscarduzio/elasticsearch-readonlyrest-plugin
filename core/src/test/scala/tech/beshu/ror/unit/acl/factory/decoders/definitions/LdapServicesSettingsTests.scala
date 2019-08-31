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
import org.scalatest.Matchers._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.{LdapAuthService, LdapAuthenticationService, LdapService}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.LdapServicesDecoder
import tech.beshu.ror.utils.LdapContainer
import tech.beshu.ror.utils.TestsUtils.StringOps
import tech.beshu.ror.utils.TaskComonad.wait30SecTaskComonad

import scala.language.postfixOps

class LdapServicesSettingsTests
  extends BaseDecoderTest(LdapServicesDecoder.ldapServicesDefinitionsDecoder)
    with ForAllTestContainer {

  private val containerLdap1 = new LdapContainer("LDAP1", "/test_example.ldif")
  private val containerLdap2 = new LdapContainer("LDAP2", "/test_example.ldif")

  override val container: MultipleContainers = MultipleContainers(containerLdap1, containerLdap2)

  "An LdapService" should {
    "be able to be loaded from config" when {
      "one LDAP service is declared" in {
        assertDecodingSuccess(
          yaml =
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
           """.stripMargin,
          assertion = { definitions =>
            definitions.items should have size 1
            val ldapService = definitions.items.head
            ldapService shouldBe a[LdapAuthService]
            ldapService.id should be(LdapService.Name("ldap1".nonempty))
          }
        )
      }
      "two LDAP services are declared" in {
        assertDecodingSuccess(
          yaml =
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
               |    port: ${containerLdap2.ldapPort}
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
           """.stripMargin,
          assertion = { definitions =>
            definitions.items should have size 2
            val ldap1Service = definitions.items.head
            ldap1Service.id should be(LdapService.Name("ldap1".nonempty))
            ldap1Service shouldBe a[LdapAuthService]

            val ldap2Service = definitions.items(1)
            ldap2Service.id should be(LdapService.Name("ldap2".nonempty))
            ldap2Service shouldBe a[LdapAuthService]
          }
        )
      }
      "LDAP authentication service definition is declared only using required fields" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    host: ${containerLdap1.ldapHost}
               |    port: ${containerLdap1.ldapSSLPort}
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    ssl_trust_all_certs: true  #this is actually not required (but we use openLDAP default cert to test)
           """.stripMargin,
          assertion = { definitions =>
            definitions.items should have size 1
            val ldapService = definitions.items.head
            ldapService.id should be(LdapService.Name("ldap1".nonempty))
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
               |    host: ${containerLdap1.ldapHost}
               |    port: ${containerLdap1.ldapSSLPort}
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=People,dc=example,dc=com"
               |    ssl_trust_all_certs: true  #this is actually not required (but we use openLDAP default cert to test)
           """.stripMargin,
          assertion = { definitions =>
            definitions.items should have size 1
            val ldapService = definitions.items.head
            ldapService.id should be(LdapService.Name("ldap1".nonempty))
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
           """.stripMargin,
          assertion = { definitions =>
            definitions.items should have size 1
            val ldapService = definitions.items.head
            ldapService shouldBe a[LdapAuthService]
            ldapService.id should be(LdapService.Name("ldap1".nonempty))
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
               |    - "ldap://${containerLdap2.ldapHost}:${containerLdap2.ldapPort}"
               |    - "ldap://${containerLdap1.ldapHost}:${containerLdap1.ldapPort}"
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
            ldapService.id should be(LdapService.Name("ldap1".nonempty))
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
               |    - "ldap://${containerLdap2.ldapHost}:${containerLdap2.ldapPort}"
               |    - "ldap://${containerLdap1.ldapHost}:${containerLdap1.ldapPort}"
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
            ldapService.id should be(LdapService.Name("ldap1".nonempty))
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "no LDAP service is defined" in {
        assertDecodingFailure(
          yaml =
            s"""
               |  ldaps:
           """.stripMargin,
          assertion = { error =>
            error should be(AclCreationError.DefinitionsLevelCreationError(Message("ldaps declared, but no definition found")))
          }
        )
      }
      "LDAP service doesn't have a name" in {
        assertDecodingFailure(
          yaml =
            s"""
               |  ldaps:
               |  - host: ${containerLdap1.ldapHost}
               |    port: ${containerLdap1.ldapPort}
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
           """.stripMargin,
          assertion = { error =>
            inside(error) { case AclCreationError.DefinitionsLevelCreationError(MalformedValue(message)) =>
              message should include(s"""host: "${containerLdap1.ldapHost}"""")
              message should include(s"""port: ${containerLdap1.ldapPort}""")
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
               |    host: ${containerLdap1.ldapHost}
               |    port: ${containerLdap1.ldapPort}
               |    ssl_enabled: false
               |    ssl_trust_all_certs: true
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
               |
               |  - name: ldap1
               |    host: ${containerLdap1.ldapHost}
               |    port: ${containerLdap1.ldapPort}
               |    ssl_enabled: false
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
           """.stripMargin,
          assertion = { error =>
            error should be(AclCreationError.DefinitionsLevelCreationError(Message("ldaps definitions must have unique identifiers. Duplicates: ldap1")))
          }
        )
      }
      "LDAP service cache TTL value is malformed" in {
        assertDecodingFailure(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    host: ${containerLdap1.ldapHost}
               |    port: ${containerLdap1.ldapPort}
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
               |    cache_ttl_in_sec: infinity
           """.stripMargin,
          assertion = { error =>
            error should be(AclCreationError.ValueLevelCreationError(Message("Cannot convert value '\"infinity\"' to duration")))
          }
        )
      }
      "LDAP service cache TTL value is negative" in {
        assertDecodingFailure(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    host: ${containerLdap1.ldapHost}
               |    port: ${containerLdap1.ldapPort}
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
               |    cache_ttl_in_sec: -10
           """.stripMargin,
          assertion = { error =>
            error should be(AclCreationError.ValueLevelCreationError(Message("Only positive values allowed. Found: -10 seconds")))
          }
        )
      }
      "only DN field of custom bind request user is defined" in {
        assertDecodingFailure(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    host: ${containerLdap1.ldapHost}
               |    port: ${containerLdap1.ldapPort}
               |    bind_dn: "cn=admin,dc=example,dc=com"
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
           """.stripMargin,
          assertion = { error =>
            error should be(AclCreationError.DefinitionsLevelCreationError(Message("'bind_dn' & 'bind_password' should be both present or both absent")))
          }
        )
      }
      "only password field of custom bind request user is defined" in {
        assertDecodingFailure(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    host: ${containerLdap1.ldapHost}
               |    port: ${containerLdap1.ldapPort}
               |    bind_password: "pass"
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
           """.stripMargin,
          assertion = { error =>
            error should be(AclCreationError.DefinitionsLevelCreationError(Message("'bind_dn' & 'bind_password' should be both present or both absent")))
          }
        )
      }
      "no LDAP host is defined" in {
        assertDecodingFailure(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    port: ${containerLdap1.ldapPort}
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
           """.stripMargin,
          assertion = { error =>
            error should be(AclCreationError.DefinitionsLevelCreationError(Message("Server information missing: use either 'host' and 'port' or 'servers'/'hosts' option.")))
          }
        )
      }
      "single host settings and multi hosts settings are used in the same time" in {
        assertDecodingFailure(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    host: ${containerLdap1.ldapHost}
               |    port: ${containerLdap1.ldapPort}
               |    servers:
               |    - "ldap://${containerLdap2.ldapHost}:${containerLdap2.ldapPort}"
               |    - "ldap://${containerLdap1.ldapHost}:${containerLdap1.ldapPort}"
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
           """.stripMargin,
          assertion = { error =>
            error should be(AclCreationError.DefinitionsLevelCreationError(Message("Cannot accept single server settings (host,port) AND multi server configuration (servers/hosts) at the same time.")))
          }
        )
      }
      "HA method is used for single LDAP host settings" in {
        assertDecodingFailure(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    host: ${containerLdap1.ldapHost}
               |    port: ${containerLdap1.ldapPort}
               |    ha: ROUND_ROBIN
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
           """.stripMargin,
          assertion = { error =>
            error should be(AclCreationError.DefinitionsLevelCreationError(Message("Please specify more than one LDAP server using 'servers'/'hosts' to use HA")))
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
               |    - "ldap://${containerLdap2.ldapHost}:${containerLdap2.ldapPort}"
               |    - "ldap://${containerLdap1.ldapHost}:${containerLdap1.ldapPort}"
               |    ha: RANDOM
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
           """.stripMargin,
          assertion = { error =>
            error should be(AclCreationError.DefinitionsLevelCreationError(Message("Unknown HA method 'RANDOM'")))
          }
        )
      }
      "one of LDAP services are unavailable" in {
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
            error should be(AclCreationError.DefinitionsLevelCreationError(Message("There was a problem with LDAP connection to: ldaps://localhost:12345")))
          }
        )
      }
      "connection pool size is malformed" in {
        assertDecodingFailure(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    host: ${containerLdap1.ldapHost}
               |    port: ${containerLdap1.ldapPort}
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
               |    connection_pool_size: -10
           """.stripMargin,
          assertion = { error =>
            error should be(AclCreationError.DefinitionsLevelCreationError(Message("Only positive values allowed. Found: -10")))
          }
        )
      }
      "connection timeout is malformed" in {
        assertDecodingFailure(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    host: ${containerLdap1.ldapHost}
               |    port: ${containerLdap1.ldapPort}
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
               |    connection_timeout_in_sec: -10
           """.stripMargin,
          assertion = { error =>
            error should be(AclCreationError.DefinitionsLevelCreationError(Message("Only positive values allowed. Found: -10 seconds")))
          }
        )
      }
      "request timeout is malformed" in {
        assertDecodingFailure(
          yaml =
            s"""
               |  ldaps:
               |  - name: ldap1
               |    host: ${containerLdap1.ldapHost}
               |    port: ${containerLdap1.ldapPort}
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
               |    request_timeout_in_sec: -10
           """.stripMargin,
          assertion = { error =>
            error should be(AclCreationError.DefinitionsLevelCreationError(Message("Only positive values allowed. Found: -10 seconds")))
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
               |    - "ldaps://${containerLdap1.ldapHost}:${containerLdap1.ldapPort}"
               |    - "ldap://${containerLdap2.ldapHost}:${containerLdap2.ldapPort}"
               |    ssl_trust_all_certs: true
               |    search_user_base_DN: "ou=People,dc=example,dc=com"
               |    search_groups_base_DN: "ou=Groups,dc=example,dc=com"
           """.stripMargin,
          assertion = { error =>
            error should be(AclCreationError.DefinitionsLevelCreationError(Message("The list of LDAP servers should be either all 'ldaps://' or all 'ldap://")))
          }
        )
      }
    }
  }

}
