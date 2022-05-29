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

import java.time.Clock

import eu.timepit.refined.auto._
import eu.timepit.refined.types.string.NonEmptyString
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider
import tech.beshu.ror.accesscontrol.blocks.definitions.{ExternalAuthenticationService, ExternalAuthorizationService}
import tech.beshu.ror.accesscontrol.blocks.mocks.{MocksProvider, NoOpMocksProvider}
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule
import tech.beshu.ror.accesscontrol.blocks.{Block, ImpersonationWarning}
import tech.beshu.ror.accesscontrol.domain.{IndexName, RorConfigurationIndex}
import tech.beshu.ror.accesscontrol.factory.{HttpClientsFactory, RawRorConfigBasedCoreFactory}
import tech.beshu.ror.boot.ReadonlyRest.RorMode
import tech.beshu.ror.configuration.RawRorConfig
import tech.beshu.ror.mocks.MockHttpClientsFactory
import tech.beshu.ror.providers._
import tech.beshu.ror.utils.SingletonLdapContainers
import tech.beshu.ror.utils.TestsUtils._

import scala.language.implicitConversions

class ImpersonationWarningsTests extends AnyWordSpec with Inside {
  "ROR config impersonation warnings" should {
    "return no warnings" when {
      "auth key block" in {
        val config =
          s"""
             |readonlyrest:
             |  access_control_rules:
             |  - name: test_block1
             |    auth_key: admin:container
             |""".stripMargin

        impersonationWarningsReader(config).read() should be(noWarnings)
      }
      "rules with hashed only password" in {
        val config =
          s"""
             |readonlyrest:
             |  access_control_rules:
             |  - name: test_block1
             |    auth_key_sha1: "admin:d27aaf7fa3c1603948bb29b7339f2559dc02019a"
             |  - name: test_block2
             |    auth_key_sha256: "admin:bdf2f78928097ae90a029c33fe06a83e3a572cb48371fb2de290d1c2ffee010b"
             |  - name: test_block3
             |    auth_key_sha512: "admin:d2e5d8a75d9474e0a10e45d616c84d5744c28e403103ebe05fbe3a00a3c9840d1e547887d2916b27cd3ca692986f7ae96b6b823b4f7772dd35180ec24e639229"
             |  - name: test_block4
             |    auth_key_pbkdf2: "admin:KhIxF5EEYkH5GPX51zTRIR4cHqhpRVALSmTaWE18mZEL2KqCkRMeMU4GR848mGq4SDtNvsybtJ/sZBuX6oFaSg=="
             |  - name: test_block5
             |    auth_key: admin:container
             |""".stripMargin

        impersonationWarningsReader(config).read() should be(noWarnings)
      }
      "ldap service is mocked" in {
        val mocksProvider = mocksProviderForLdapFrom(
          Map(LdapService.Name("ldap1") -> Map.empty)
        )

        val config =
          s"""
             |readonlyrest:
             |
             |  access_control_rules:
             |
             |  - name: test_block1
             |    ldap_auth:
             |      name: "ldap1"
             |      groups: ["group3"]
             |
             |  - name: test_block2
             |    ldap_authentication:
             |      name: "ldap1"
             |
             |  - name: test_block3
             |    auth_key: "user:pass"
             |    ldap_authorization:
             |      name: "ldap1"
             |      groups: ["group3"]
             |
             |  ldaps:
             |  - name: ldap1
             |    host: ${SingletonLdapContainers.ldap1.ldapHost}
             |    port: ${SingletonLdapContainers.ldap1.ldapPort}
             |    ssl_enabled: false
             |    search_user_base_DN: "ou=People,dc=example,dc=com"
             |    search_groups_base_DN: "ou=People,dc=example,dc=com"
             |""".stripMargin

        impersonationWarningsReader(config, mocksProvider).read() should be(noWarnings)
      }
      "external authentication service is mocked" in {
        val mocksProvider = mocksProviderForExternalAuthnServiceFrom(
          Map(ExternalAuthenticationService.Name("ext1") -> Set.empty)
        )

        val config =
          s"""
             |readonlyrest:
             |
             |  access_control_rules:
             |
             |  - name: test_block1
             |    external_authentication: "ext1"
             |
             |  external_authentication_service_configs:
             |
             |  - name: "ext1"
             |    authentication_endpoint: "http://localhost:8080/auth1"
             |""".stripMargin

        impersonationWarningsReader(config, mocksProvider).read() should be(noWarnings)
      }
      "external authorization service is mocked" in {
        val mocksProvider = mocksProviderForExternalAuthzServiceFrom(
          Map(ExternalAuthorizationService.Name("GroupsService1") -> Map.empty)
        )

        val config =
          """
            |readonlyrest:
            |
            |  access_control_rules:
            |
            |  - name: test_block1
            |    auth_key: "user:pass"
            |    groups_provider_authorization:
            |      user_groups_provider: "GroupsService1"
            |      groups: ["group3"]
            |      users: user1
            |
            |  user_groups_providers:
            |
            |  - name: GroupsService1
            |    groups_endpoint: "http://localhost:8080/groups"
            |    auth_token_name: "user"
            |    auth_token_passed_as: QUERY_PARAM
            |    response_groups_json_path: "$..groups[?(@.name)].name"
            |
            |""".stripMargin

        impersonationWarningsReader(config, mocksProvider).read() should be(noWarnings)
      }
    }
    "return warnings" when {
      "rules with hashed username and password" in {
        val config =
          s"""
             |readonlyrest:
             |  access_control_rules:
             |  - name: test_block1
             |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
             |  - name: test_block2
             |    auth_key_sha256: "bdf2f78928097ae90a029c33fe06a83e3a572cb48371fb2de290d1c2ffee010b"
             |  - name: test_block3
             |    auth_key_sha512: "d2e5d8a75d9474e0a10e45d616c84d5744c28e403103ebe05fbe3a00a3c9840d1e547887d2916b27cd3ca692986f7ae96b6b823b4f7772dd35180ec24e639229"
             |  - name: test_block4
             |    auth_key_pbkdf2: "KhIxF5EEYkH5GPX51zTRIR4cHqhpRVALSmTaWE18mZEL2KqCkRMeMU4GR848mGq4SDtNvsybtJ/sZBuX6oFaSg=="
             |  - name: test_block5
             |    auth_key: admin:container
             |""".stripMargin

        impersonationWarningsReader(config).read() should be(List(
          fullyHashedCredentialsWarning("test_block1", "auth_key_sha1"),
          fullyHashedCredentialsWarning("test_block2", "auth_key_sha256"),
          fullyHashedCredentialsWarning("test_block3", "auth_key_sha512"),
          fullyHashedCredentialsWarning("test_block4", "auth_key_pbkdf2"),
        ))
      }
      "ldap service is not mocked" in {
        val config =
          s"""
             |readonlyrest:
             |
             |  access_control_rules:
             |
             |  - name: test_block1
             |    ldap_auth:
             |      name: "ldap1"
             |      groups: ["group3"]
             |
             |  - name: test_block2
             |    ldap_authentication:
             |      name: "ldap1"
             |
             |  - name: test_block3
             |    auth_key: "user:pass"
             |    ldap_authorization:
             |      name: "ldap1"
             |      groups: ["group3"]
             |
             |  ldaps:
             |  - name: ldap1
             |    host: ${SingletonLdapContainers.ldap1.ldapHost}
             |    port: ${SingletonLdapContainers.ldap1.ldapPort}
             |    ssl_enabled: false
             |    search_user_base_DN: "ou=People,dc=example,dc=com"
             |    search_groups_base_DN: "ou=People,dc=example,dc=com"
             |""".stripMargin

        val hint = "Configure a mock of an LDAP service with ID [ldap1]"

        impersonationWarningsReader(config).read() should be(List(
          notMockedServiceWarning("test_block1", "ldap_auth", hint),
          notMockedServiceWarning("test_block2", "ldap_authentication", hint),
          notMockedServiceWarning("test_block3", "ldap_authorization", hint),
        ))
      }
      "external authentication service is not mocked" in {
        val config =
          s"""
             |readonlyrest:
             |
             |  access_control_rules:
             |
             |  - name: test_block1
             |    external_authentication: "ext1"
             |
             |  external_authentication_service_configs:
             |
             |  - name: "ext1"
             |    authentication_endpoint: "http://localhost:8080/auth1"
             |""".stripMargin

        val hint = "Configure a mock of an external authentication service with ID [ext1]"
        impersonationWarningsReader(config, NoOpMocksProvider).read() should be(List(
          notMockedServiceWarning("test_block1", "external_authentication", hint)
        ))
      }
      "external authorization service is not mocked" in {
        val config =
          """
            |readonlyrest:
            |
            |  access_control_rules:
            |
            |  - name: test_block1
            |    auth_key: "user:pass"
            |    groups_provider_authorization:
            |      user_groups_provider: "GroupsService1"
            |      groups: ["group3"]
            |      users: user1
            |
            |  user_groups_providers:
            |
            |  - name: GroupsService1
            |    groups_endpoint: "http://localhost:8080/groups"
            |    auth_token_name: "user"
            |    auth_token_passed_as: QUERY_PARAM
            |    response_groups_json_path: "$..groups[?(@.name)].name"
            |
            |""".stripMargin

        val hint = "Configure a mock of an external authorization service with ID [GroupsService1]"
        impersonationWarningsReader(config, NoOpMocksProvider).read() should be(List(
          notMockedServiceWarning("test_block1", "groups_provider_authorization", hint)
        ))
      }
      "impersonation not supported by rule" when {
        "jwt rule" in {
          val config =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    jwt_auth: jwt1
              |
              |  jwt:
              |
              |  - name: jwt1
              |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
              |
              |""".stripMargin

          impersonationWarningsReader(config, NoOpMocksProvider).read() should be(List(
            impersonationNotSupportedWarning("test_block1", "jwt_auth")
          ))
        }
        "ror kbn auth rule" in {
          val config =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    ror_kbn_auth: kbn1
              |
              |  ror_kbn:
              |
              |  - name: kbn1
              |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
              |
              |""".stripMargin

          impersonationWarningsReader(config, NoOpMocksProvider).read() should be(List(
            impersonationNotSupportedWarning("test_block1", "ror_kbn_auth")
          ))
        }
      }
    }
  }

  private def noWarnings = List.empty

  private def fullyHashedCredentialsWarning(blockName: String, ruleName: String) = {
    warning(
      blockName,
      ruleName,
      message = "The rule contains fully hashed username and password. It doesn't support impersonation in this configuration",
      hint = s"You can use second version of the rule and use not hashed username. Like that: `$ruleName: USER_NAME:hash(PASSWORD)"
    )
  }

  private def notMockedServiceWarning(blockName: String, ruleName: String, hint: String) = {
    warning(
      blockName,
      ruleName,
      message = s"The rule '$ruleName' will not match during impersonation until a mock of service is not configured",
      hint = hint
    )
  }

  private def impersonationNotSupportedWarning(blockName: String, ruleName: String) = {
    warning(
      blockName,
      ruleName,
      message = "Impersonation is not supported by this rule by default",
      hint = ""
    )
  }

  private def warning(blockName: String, ruleName: String, message: String, hint: String) = {
    ImpersonationWarning(
      Block.Name(blockName),
      Rule.Name(ruleName),
      NonEmptyString.unsafeFrom(message),
      hint
    )
  }

  private def impersonationWarningsReader(config: String, mocksProvider: MocksProvider = NoOpMocksProvider) = {
    val rorConfig = rorConfigFromUnsafe(config)
    inside(createCore(config = rorConfig, mocksProvider = mocksProvider)) {
      case Right(core) =>
        core.rorConfig.impersonationWarningsReader
    }
  }

  private implicit val dummyRequestID: RequestId = RequestId("dummy")

  private def createCore(config: RawRorConfig,
                         clientsFactory: HttpClientsFactory = MockHttpClientsFactory,
                         mocksProvider: MocksProvider = NoOpMocksProvider) = {
    factory
      .createCoreFrom(
        config,
        RorConfigurationIndex(IndexName.Full(".readonlyrest")),
        clientsFactory,
        new UnboundidLdapConnectionPoolProvider(),
        mocksProvider
      )
      .runSyncUnsafe()
  }

  private val factory = {
    implicit val clock: Clock = Clock.systemUTC()
    implicit val uuidProvider: UuidProvider = JavaUuidProvider
    implicit val provider: EnvVarsProvider = OsEnvVarsProvider
    implicit val propertiesProvider: PropertiesProvider = JvmPropertiesProvider
    new RawRorConfigBasedCoreFactory(RorMode.Plugin)
  }
}
