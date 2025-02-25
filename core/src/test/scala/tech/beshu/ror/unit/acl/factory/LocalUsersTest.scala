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
package tech.beshu.ror.unit.acl.factory
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider
import tech.beshu.ror.accesscontrol.blocks.mocks.NoOpMocksProvider
import tech.beshu.ror.accesscontrol.domain.{IndexName, LocalUsers, RorConfigurationIndex, User}
import tech.beshu.ror.accesscontrol.factory.{HttpClientsFactory, RawRorConfigBasedCoreFactory}
import tech.beshu.ror.configuration.{EnvironmentConfig, RawRorConfig}
import tech.beshu.ror.mocks.{MockHttpClientsFactory, MockLdapConnectionPoolProvider}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.SingletonLdapContainers
import tech.beshu.ror.utils.TestsUtils.*

class LocalUsersTest extends AnyWordSpec with Inside {

  "ROR config local users" should {
    "return info that all users are resolved" when {
      "auth key block" in {
        assertLocalUsersFromConfig(
          s"""
             |readonlyrest:
             |  access_control_rules:
             |  - name: test_block1
             |    auth_key: admin:container
             |""".stripMargin,
          expected = allUsersResolved(Set(User.Id("admin")))
        )
      }
      "username used in two rules" in {
        assertLocalUsersFromConfig(
          s"""
             |readonlyrest:
             |  access_control_rules:
             |  - name: test_block1
             |    auth_key: admin:container
             |  - name: test_block2
             |    auth_key: admin:container
             |""".stripMargin,
          expected = allUsersResolved(Set(User.Id("admin")))
        )
      }
      "different users defined in rules" in {
        assertLocalUsersFromConfig(
          s"""
             |readonlyrest:
             |  access_control_rules:
             |  - name: test_block1
             |    auth_key: admin:container
             |  - name: test_block2
             |    auth_key: admin1:container
             |""".stripMargin,
          expected = allUsersResolved(Set(User.Id("admin"), User.Id("admin1")))
        )
      }
      "hashed is only password" in {
        assertLocalUsersFromConfig(
          s"""
             |readonlyrest:
             |  access_control_rules:
             |  - name: test_block1
             |    auth_key_sha1: "user1:d27aaf7fa3c1603948bb29b7339f2559dc02019a"
             |  - name: test_block2
             |    auth_key_sha256: "user2:bdf2f78928097ae90a029c33fe06a83e3a572cb48371fb2de290d1c2ffee010b"
             |""".stripMargin,
          allUsersResolved(Set(User.Id("user1"), User.Id("user2")))
        )
      }
      "'proxy_auth' rule" in {
        val config =
          s"""
             |readonlyrest:
             |    access_control_rules:
             |
             |    - name: "::Tweets::"
             |      methods: GET
             |      indices: ["twitter"]
             |      proxy_auth:
             |        proxy_auth_config: "proxy1"
             |        users: ["admin"]
             |      groups_provider_authorization:
             |        user_groups_provider: "GroupsService"
             |        groups: ["group3"]
             |
             |    - name: "::Facebook posts::"
             |      methods: GET
             |      indices: ["facebook"]
             |      proxy_auth:
             |        proxy_auth_config: "proxy1"
             |        users: ["dev"]
             |      groups_provider_authorization:
             |        user_groups_provider: "GroupsService"
             |        groups: ["group1"]
             |        cache_ttl_in_sec: 60
             |
             |    proxy_auth_configs:
             |
             |    - name: "proxy1"
             |      user_id_header: "X-Auth-Token"                           # default X-Forwarded-User
             |
             |    user_groups_providers:
             |
             |    - name: GroupsService
             |      groups_endpoint: "http://localhost:8080/groups"
             |      auth_token_name: "token"
             |      auth_token_passed_as: QUERY_PARAM                        # HEADER OR QUERY_PARAM
             |      response_group_ids_json_path: "$$..groups[?(@.id)].id"   # see: https://github.com/json-path/JsonPath
             |      cache_ttl_in_sec: 60
             |      http_connection_settings:
             |        connection_timeout_in_sec: 5                           # default 2
             |        socket_timeout_in_sec: 3                               # default 5
             |        connection_request_timeout_in_sec: 3                   # default 5
             |        connection_pool_size: 10                               # default 30
             |""".stripMargin

        assertLocalUsersFromConfig(
          config,
          allUsersResolved(Set(User.Id("admin"), User.Id("dev")))
        )
      }
      "users section defined without wildcard patterns" when {
        "auth_key rules used" in {
          val config =
            s"""
               |readonlyrest:
               |  access_control_rules:
               |  - name: test_block1
               |    auth_key: admin:container
               |
               |  users:
               |  - username: user1
               |    groups: ["group1", "group3"]
               |    auth_key: "user1:pass"
               |
               |  - username: user2
               |    groups: ["group2", "group4"]
               |    auth_key: "user2:pass"
               |
               |  - username: user4
               |    groups: ["group5", "group6"]
               |    auth_key: "user4:pass"
               |""".stripMargin

          assertLocalUsersFromConfig(config, allUsersResolved(Set(
            User.Id("user1"), User.Id("user2"), User.Id("user4"), User.Id("admin")
          )))
        }
        "ldap_authentication rule used" in {
          val config =
            s"""
               |readonlyrest:
               |  access_control_rules:
               |  - name: test_block1
               |    auth_key: admin:container
               |
               |  users:
               |    - username: cartman
               |      groups: ["local_group1", "local_group3"]
               |      ldap_authentication: "ldap1"
               |
               |    - username: Bìlbö Bággįnš
               |      groups: ["local_group1"]
               |      ldap_authentication: "ldap1"
               |
               |    - username: bong
               |      groups: ["local_group2"]
               |      ldap_authentication: "ldap1"
               |
               |    - username: morgan
               |      groups: ["local_group2", "local_group3"]
               |      ldap_authentication: "ldap1"
               |
               |  ldaps:
               |   - name: ldap1
               |     host: ${SingletonLdapContainers.ldap1.ldapHost}
               |     port: ${SingletonLdapContainers.ldap1.ldapPort}
               |     ssl_enabled: false
               |     users:
               |       search_user_base_DN: "ou=People,dc=example,dc=com"
               |     groups:
               |       search_groups_base_DN: "ou=People,dc=example,dc=com"
               |""".stripMargin

          val rorConfig = rorConfigFromUnsafe(config)
          inside(createCore(rorConfig, new UnboundidLdapConnectionPoolProvider())) {
            case Right(core) =>
              core.rorConfig.localUsers should be(allUsersResolved(Set(
                User.Id("admin"), User.Id("cartman"), User.Id("Bìlbö Bággįnš"), User.Id("bong"), User.Id("morgan")
              )))
          }
        }
      }
      "impersonators section defined with users" in {
        val config =
          s"""
             |readonlyrest:
             |  access_control_rules:
             |  - name: test_block1
             |    auth_key: admin:container
             |
             |  impersonation:
             |   - impersonator: devAdmin
             |     auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
             |     users: ["user3"]
             |   - impersonator: devAdmin2
             |     auth_key: devAdmin2:pass
             |     users: ["user1", "user2"]
             |   - impersonator: devAdmin3
             |     auth_key: devAdmin3:pass
             |     users: ["*", "user*"]
             |""".stripMargin
        assertLocalUsersFromConfig(config, expected = allUsersResolved(Set(
          User.Id("admin"), User.Id("user1"), User.Id("user2"), User.Id("user3")
        )))
      }
    }
    "return info that unknown users in config" when {
      "hashed username and password" in {
        val config =
          s"""
             |readonlyrest:
             |  access_control_rules:
             |  - name: test_block1
             |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
             |  - name: test_block2
             |    auth_key_sha256: "bdf2f78928097ae90a029c33fe06a83e3a572cb48371fb2de290d1c2ffee010b"
             |  - name: test_block3
             |    auth_key: admin:container
             |""".stripMargin
        assertLocalUsersFromConfig(config, expected = withUnknownUsers(Set(User.Id("admin"))))
      }
      "there is some user with hashed credentials" in {
        val config =
          s"""
             |readonlyrest:
             |  access_control_rules:
             |  - name: test_block1
             |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
             |  - name: test_block2
             |    auth_key: admin:container
             |""".stripMargin
        assertLocalUsersFromConfig(config, withUnknownUsers(Set(User.Id("admin"))))
      }
      "users section defined with wildcard patterns" in {
        val config =
          s"""
             |readonlyrest:
             |  access_control_rules:
             |  - name: test_block1
             |    auth_key: admin:container
             |
             |  users:
             |  - username: user1
             |    groups: ["group1", "group3"]
             |    auth_key: "user1:pass"
             |
             |  - username: "*"
             |    groups: ["group2", "group4"]
             |    auth_key: "user2:pass"
             |
             |  - username: "*"
             |    groups: ["group5", "group6"]
             |    auth_key: "user4:pass"
             |
             |  - username: "*"
             |    groups: ["group5", "group6"]
             |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
             |""".stripMargin
        assertLocalUsersFromConfig(config, expected = withUnknownUsers(Set(
          User.Id("admin"), User.Id("user1"), User.Id("user2"), User.Id("user4")
        )))
      }
    }
  }

  private def withUnknownUsers(users: Set[User.Id]) = LocalUsers(users, unknownUsers = true)

  private def allUsersResolved(users: Set[User.Id]) = LocalUsers(users, unknownUsers = false)

  private def assertLocalUsersFromConfig(config: String, expected: LocalUsers) = {
    val rorConfig = rorConfigFromUnsafe(config)
    inside(createCore(rorConfig)) {
      case Right(core) =>
        core.rorConfig.localUsers should be(expected)
    }
  }

  private def createCore(config: RawRorConfig,
                         ldapConnectionPoolProvider: UnboundidLdapConnectionPoolProvider = MockLdapConnectionPoolProvider,
                         clientsFactory: HttpClientsFactory = MockHttpClientsFactory) = {
    factory
      .createCoreFrom(
        config,
        RorConfigurationIndex(IndexName.Full(".readonlyrest")),
        clientsFactory,
        ldapConnectionPoolProvider,
        NoOpMocksProvider
      )
      .runSyncUnsafe()
  }

  private val factory = {
    implicit val environmentConfig: EnvironmentConfig = EnvironmentConfig.default
    new RawRorConfigBasedCoreFactory(defaultEsVersionForTests)
  }

}
