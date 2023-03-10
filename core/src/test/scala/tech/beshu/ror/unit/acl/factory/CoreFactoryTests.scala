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

import cats.data.NonEmptyList
import eu.timepit.refined.auto._
import eu.timepit.refined.types.string.NonEmptyString
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.acl.AccessControlList
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.mocks.NoOpMocksProvider
import tech.beshu.ror.accesscontrol.domain.{Header, IndexName, RorConfigurationIndex}
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory.HttpClient
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.{BlocksLevelCreationError, DefinitionsLevelCreationError, RulesLevelCreationError}
import tech.beshu.ror.accesscontrol.factory.{Core, HttpClientsFactory, RawRorConfigBasedCoreFactory}
import tech.beshu.ror.boot.ReadonlyRest.RorMode
import tech.beshu.ror.configuration.RawRorConfig
import tech.beshu.ror.mocks.{MockHttpClientsFactory, MockHttpClientsFactoryWithFixedHttpClient, MockLdapConnectionPoolProvider}
import tech.beshu.ror.providers._
import tech.beshu.ror.utils.TestsUtils._

class CoreFactoryTests extends AnyWordSpec with Inside with MockFactory {

  private val factory = {
    implicit val clock: Clock = Clock.systemUTC()
    implicit val uuidProvider: UuidProvider = JavaUuidProvider
    implicit val provider: EnvVarsProvider = OsEnvVarsProvider
    implicit val propertiesProvider: PropertiesProvider = JvmPropertiesProvider
    new RawRorConfigBasedCoreFactory(RorMode.Plugin)
  }

  "A RorAclFactory" should {
    "return proxy auth configs error" when {
      "the section exists, but not contain any element" in {
        val config = rorConfigFromUnsafe(
          """
            |readonlyrest:
            |
            |  access_control_rules:
            |
            |  - name: test_block
            |    type: allow
            |    auth_key: admin:container
            |
            |  proxy_auth_configs:
            |
            |""".stripMargin)
        val acl = createCore(config)
        acl should be(Left(NonEmptyList.one(DefinitionsLevelCreationError(Message("proxy_auth_configs declared, but no definition found")))))
      }
      "the section contains proxies with the same names" in {
        val config = rorConfigFromUnsafe(
          """
            |readonlyrest:
            |
            |  access_control_rules:
            |
            |  - name: test_block
            |    type: allow
            |    auth_key: admin:container
            |
            |  proxy_auth_configs:
            |
            |  - name: "proxy1"
            |    user_id_header: "X-Auth-Token2"
            |
            |  - name: "proxy1"
            |    user_id_header: "X-Auth-Token1"
            |
            |""".stripMargin)
        val acl = createCore(config)
        acl should be(Left(NonEmptyList.one(DefinitionsLevelCreationError(Message("proxy_auth_configs definitions must have unique identifiers. Duplicates: proxy1")))))
      }
      "proxy definition has no name" in {
        val config = rorConfigFromUnsafe(
          """
            |readonlyrest:
            |
            |  access_control_rules:
            |
            |  - name: test_block
            |    type: allow
            |    auth_key: admin:container
            |
            |  proxy_auth_configs:
            |
            |  - desc: "proxy1"
            |    user_id_header: "X-Auth-Token2"
            |
            |""".stripMargin)
        val acl = createCore(config)
        acl should be(Left(NonEmptyList.one(DefinitionsLevelCreationError(MalformedValue(
          """desc: "proxy1"
            |user_id_header: "X-Auth-Token2"
            |""".stripMargin
        )))))
      }
      "proxy definition has no user id" in {
        val config = rorConfigFromUnsafe(
          """
            |readonlyrest:
            |
            |  access_control_rules:
            |
            |  - name: test_block
            |    type: allow
            |    auth_key: admin:container
            |
            |  proxy_auth_configs:
            |
            |  - name: "proxy1"
            |
            |""".stripMargin)
        val acl = createCore(config)
        acl should be(Left(NonEmptyList.one(DefinitionsLevelCreationError(MalformedValue("name: \"proxy1\"\n")))))
      }
    }
    "return headers list" when {
      "the section is not defined" in {
        val config = rorConfigFromUnsafe(
          """
            |readonlyrest:
            |
            |  access_control_rules:
            |
            |  - name: test_block
            |    type: allow
            |    auth_key: admin:container
            |
            |""".stripMargin)
        val acl = createCore(config)
        val obfuscatedHeaders = acl.right.get.accessControl.staticContext.obfuscatedHeaders
        obfuscatedHeaders shouldEqual Set(Header.Name.authorization)
      }
      "the section exists, and obfuscated header is not defined" in {
        val config = rorConfigFromUnsafe(
          """
            |readonlyrest:
            |
            |  access_control_rules:
            |
            |  - name: test_block
            |    type: allow
            |    auth_key: admin:container
            |
            |  obfuscated_headers: []
            |""".stripMargin)
        val acl = createCore(config)
        val headers = acl.right.get.accessControl.staticContext.obfuscatedHeaders
        headers shouldBe empty
      }
      "the section exists, and obfuscated header is defined" in {
        val config = rorConfigFromUnsafe(
          """
            |readonlyrest:
            |
            |  access_control_rules:
            |
            |  - name: test_block
            |    type: allow
            |    auth_key: admin:container
            |
            |  obfuscated_headers:
            |  - CorpoAuth
            |""".stripMargin)
        val acl = createCore(config)
        val headers = acl.right.get.accessControl.staticContext.obfuscatedHeaders
        headers should have size 1
        headers.head should be(Header.Name(NonEmptyString.unsafeFrom("CorpoAuth")))
      }
    }
    "return blocks level error" when {
      "there is no `access_control_rules` section" in {
        val config = rorConfigFromUnsafe(
          """
            |readonlyrest:
            |
            |  proxy_auth_configs:
            |
            |  - name: "proxy1"
            |    user_id_header: "X-Auth-Token2"
            |
            |  - name: "proxy2"
            |    user_id_header: "X-Auth-Token1"
            |
            |""".stripMargin)
        val acl = createCore(config)
        acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(Message(s"No access_control_rules section found")))))
      }
      "there is `access_control_rules` section defined, but without any block" in {
        val config = rorConfigFromUnsafe(
          """
            |readonlyrest:
            |
            |  access_control_rules:
            |
            |  proxy_auth_configs:
            |
            |  - name: "proxy1"
            |    user_id_header: "X-Auth-Token2"
            |
            |  - name: "proxy2"
            |    user_id_header: "X-Auth-Token1"
            |
            |""".stripMargin)
        val acl = createCore(config)
        acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(Message(s"access_control_rules defined, but no block found")))))
      }
      "two blocks has the same names" in {
        val config = rorConfigFromUnsafe(
          """
            |readonlyrest:
            |
            |  access_control_rules:
            |
            |  - name: test_block
            |    auth_key: admin:container
            |
            |  - name: test_block
            |    type: allow
            |    auth_key: admin:container
            |
            |  - name: test_block2
            |    auth_key: admin:container
            |
            |  - name: test_block2
            |    type: allow
            |    auth_key: admin:container
            |
            |""".stripMargin)
        val acl = createCore(config)
        acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(Message(s"Blocks must have unique names. Duplicates: test_block,test_block2")))))
      }
      "block has no name" in {
        val config = rorConfigFromUnsafe(
          """
            |readonlyrest:
            |
            |  access_control_rules:
            |
            |  - type: allow
            |    auth_key: admin:container
            |
            |""".stripMargin)
        val acl = createCore(config)
        acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(MalformedValue(
          """type: "allow"
            |auth_key: "admin:container"
            |""".stripMargin
        )))))
      }
      "block has unknown type" in {
        val config = rorConfigFromUnsafe(
          """
            |readonlyrest:
            |
            |  access_control_rules:
            |
            |  - name: test_block
            |    type: unknown
            |    auth_key: admin:container
            |
            |""".stripMargin)
        val acl = createCore(config)
        acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(Message("Unknown block policy type: unknown")))))
      }
      "block has unknown verbosity" in {
        val config = rorConfigFromUnsafe(
          """
            |readonlyrest:
            |
            |  access_control_rules:
            |
            |  - name: test_block
            |    verbosity: unknown
            |    auth_key: admin:container
            |
            |""".stripMargin)
        val acl = createCore(config)
        acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(Message("Unknown verbosity value: unknown")))))
      }
      "block has authorization rule, but no authentication rule" in {
        val config = rorConfigFromUnsafe(
          """
            |readonlyrest:
            |
            |  access_control_rules:
            |
            |  - name: test_block
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
            |""".stripMargin)
        val acl = createCore(config, new MockHttpClientsFactoryWithFixedHttpClient(mock[HttpClient]))
        acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(Message("The 'test_block' block contains an authorization rule, but not an authentication rule. This does not mean anything if you don't also set some authentication rule.")))))
      }
      "block has many authentication rules" in {
        val config = rorConfigFromUnsafe(
          """
            |readonlyrest:
            |
            |  access_control_rules:
            |
            |  - name: test_block
            |    auth_key: "user2:pass"
            |    proxy_auth:
            |      proxy_auth_config: "proxy1"
            |      users: ["user1-proxy-id"]
            |    indices: ["g12_index"]
            |
            |  proxy_auth_configs:
            |
            |  - name: "proxy1"
            |    user_id_header: "X-Auth-Token"
            |
    """.stripMargin)
        val acl = createCore(config, new MockHttpClientsFactoryWithFixedHttpClient(mock[HttpClient]))
        acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(Message("The 'test_block' block should contain only one authentication rule, but contains: [auth_key,proxy_auth]")))))
      }
      "block has kibana access rule together with actions rule" in {
        val config = rorConfigFromUnsafe(
          """
            |readonlyrest:
            |
            |  access_control_rules:
            |
            |  - name: test_block
            |    kibana:
            |      access: admin
            |    actions: ["cluster:*"]
            |""".stripMargin)
        val acl = createCore(config, new MockHttpClientsFactoryWithFixedHttpClient(mock[HttpClient]))
        acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(Message(
          "The 'test_block' block contains 'kibana' rule (or deprecated 'kibana_access' rule) and 'actions' rule. These two cannot be used together in one block."
        )))))
      }
      "block uses user variable without defining authentication rule beforehand" in {
        val config = rorConfigFromUnsafe(
          """
            |readonlyrest:
            |
            |  access_control_rules:
            |
            |  - name: test_block
            |    uri_re: "some_@{user}"
            |""".stripMargin)
        val acl = createCore(config, new MockHttpClientsFactoryWithFixedHttpClient(mock[HttpClient]))
        acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(Message("The 'test_block' block doesn't meet requirements for defined variables. Variable used to extract user requires one of the rules defined in block to be authentication rule")))))
      }
      "'groups' rule uses jwt variable without defining `jwt_auth` rule beforehand" in {
        val config = rorConfigFromUnsafe(
          """
            |readonlyrest:
            |
            |  access_control_rules:
            |
            |  - name: test_block
            |    groups: ["@explode{jwt:roles}"]
            |
            |  users:
            |
            |  - username: "*"
            |    groups: ["group1"]
            |    auth_key: "user2:pass"
            |
            |""".stripMargin)
        val acl = createCore(config, new MockHttpClientsFactoryWithFixedHttpClient(mock[HttpClient]))
        acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(Message("The 'test_block' block doesn't meet requirements for defined variables. JWT variables are not allowed to be used in Groups rule")))))
      }
      "old style kibana rules cannot be mixed with new style kibana rule" when {
        "kibana_access and kibana rules are mixed" in {
          val config = rorConfigFromUnsafe(
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block
              |    kibana_access: ro
              |    kibana:
              |      access: ro
              |      kibana_index: .kibana_custom
              |
              |""".stripMargin)
          val acl = createCore(config, new MockHttpClientsFactoryWithFixedHttpClient(mock[HttpClient]))
          acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(Message(
            """The 'test_block' block contains 'kibana' rule and 'kibana_access' rule. The second one is deprecated. The first one offers all the second one is able to provide."""
          )))))
        }
        "kibana_index and kibana rules are mixed" in {
          val config = rorConfigFromUnsafe(
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block
              |    kibana_index: .kibana_custom
              |    kibana:
              |      access: ro
              |      kibana_index: .kibana_custom
              |
              |""".stripMargin)
          val acl = createCore(config, new MockHttpClientsFactoryWithFixedHttpClient(mock[HttpClient]))
          acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(Message(
            """The 'test_block' block contains 'kibana' rule and 'kibana_index' rule. The second one is deprecated. The first one offers all the second one is able to provide."""
          )))))
        }
        "kibana_hide_apps and kibana rules are mixed" in {
          val config = rorConfigFromUnsafe(
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block
              |    kibana_hide_apps: ["app1"]
              |    kibana:
              |      access: ro
              |      kibana_index: .kibana_custom
              |
              |""".stripMargin)
          val acl = createCore(config, new MockHttpClientsFactoryWithFixedHttpClient(mock[HttpClient]))
          acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(Message(
            """The 'test_block' block contains 'kibana' rule and 'kibana_hide_apps' rule. The second one is deprecated. The first one offers all the second one is able to provide."""
          )))))
        }
        "kibana_template_index and kibana rules are mixed" in {
          val config = rorConfigFromUnsafe(
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block
              |    kibana_template_index: ".kibana_template_index"
              |    kibana:
              |      access: ro
              |      kibana_index: .kibana_custom
              |
              |""".stripMargin)
          val acl = createCore(config, new MockHttpClientsFactoryWithFixedHttpClient(mock[HttpClient]))
          acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(Message(
            """The 'test_block' block contains 'kibana' rule and 'kibana_template_index' rule. The second one is deprecated. The first one offers all the second one is able to provide."""
          )))))
        }
      }
    }
    "return rule level error" when {
      "no rules are defined in block" in {
        val config = rorConfigFromUnsafe(
          """
            |readonlyrest:
            |
            |  access_control_rules:
            |
            |  - name: test_block
            |    type: allow
            |
            |""".stripMargin)
        val acl = createCore(config)
        acl should be(Left(NonEmptyList.one(RulesLevelCreationError(Message("No rules defined in block")))))
      }
      "block has unknown rules" in {
        val config = rorConfigFromUnsafe(
          """
            |readonlyrest:
            |
            |  access_control_rules:
            |
            |  - name: test_block
            |    unknown_rule1: value2
            |    unknown_rule2: value1
            |
            |""".stripMargin)
        val acl = createCore(config)
        acl should be(Left(NonEmptyList.one(RulesLevelCreationError(Message("Unknown rules: unknown_rule1,unknown_rule2")))))
      }
    }
    "return ACL with blocks defined in config" in {
      val config = rorConfigFromUnsafe(
        """
          |readonlyrest:
          |
          |  access_control_rules:
          |
          |  - name: test_block1
          |    type: forbid
          |    verbosity: info
          |    auth_key: admin:container
          |
          |  - name: test_block2
          |    type: allow
          |    verbosity: error
          |    auth_key: admin:container
          |
          |""".stripMargin)

      inside(createCore(config)) {
        case Right(Core(acl: AccessControlList, _)) =>
          val firstBlock = acl.blocks.head
          firstBlock.name should be(Block.Name("test_block1"))
          firstBlock.policy should be(Block.Policy.Forbid)
          firstBlock.verbosity should be(Block.Verbosity.Info)
          firstBlock.rules should have size 1

          val secondBlock = acl.blocks.tail.head
          secondBlock.name should be(Block.Name("test_block2"))
          secondBlock.policy should be(Block.Policy.Allow)
          secondBlock.verbosity should be(Block.Verbosity.Error)
          secondBlock.rules should have size 1
      }
    }
    "return ACL with blocks defined in config" when {
      "each block meets requirements for variables" in {
        val config = rorConfigFromUnsafe(
          """
            |readonlyrest:
            |
            |  access_control_rules:
            |
            |  - name: test_block1
            |    auth_key: admin:container
            |    indices: ["test", "other_@{user}"]
            |
            |  - name: test_block2
            |    uri_re: "/endpoint_@{acl:current_group}"
            |""".stripMargin)

        inside(createCore(config, new MockHttpClientsFactoryWithFixedHttpClient(mock[HttpClient]))) {
          case Right(Core(acl: AccessControlList, _)) =>
            val firstBlock = acl.blocks.head
            firstBlock.name should be(Block.Name("test_block1"))
            firstBlock.rules should have size 2

            val secondBlock = acl.blocks.tail.head
            secondBlock.name should be(Block.Name("test_block2"))
            secondBlock.rules should have size 1
        }
      }
    }
  }

  private def createCore(config: RawRorConfig,
                         clientsFactory: HttpClientsFactory = MockHttpClientsFactory) = {
    factory
      .createCoreFrom(
        config,
        RorConfigurationIndex(IndexName.Full(".readonlyrest")),
        clientsFactory,
        MockLdapConnectionPoolProvider,
        NoOpMocksProvider
      )
      .runSyncUnsafe()
  }
}
