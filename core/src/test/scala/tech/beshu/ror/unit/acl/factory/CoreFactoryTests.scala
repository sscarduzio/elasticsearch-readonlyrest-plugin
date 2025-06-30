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

import cats.data.NonEmptyList
import eu.timepit.refined.types.string.NonEmptyString
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.EnabledAccessControlList
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.mocks.NoOpMocksProvider
import tech.beshu.ror.accesscontrol.domain.{Header, IndexName, RorConfigurationIndex}
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory.HttpClient
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.{BlocksLevelCreationError, RulesLevelCreationError}
import tech.beshu.ror.accesscontrol.factory.{Core, CoreFactory, HttpClientsFactory, RawRorConfigBasedCoreFactory}
import tech.beshu.ror.configuration.RawRorConfig
import tech.beshu.ror.mocks.{MockHttpClientsFactory, MockHttpClientsFactoryWithFixedHttpClient, MockLdapConnectionPoolProvider}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.*

class CoreFactoryTests extends AnyWordSpec with Inside with MockFactory {

  private val factory: CoreFactory = {
    implicit val systemContext: SystemContext = SystemContext.default
    new RawRorConfigBasedCoreFactory(defaultEsVersionForTests)
  }

  "A RorAclFactory" should {
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
        val obfuscatedHeaders = acl.toOption.get.accessControl.staticContext.obfuscatedHeaders
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
        val headers = acl.toOption.get.accessControl.staticContext.obfuscatedHeaders
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
        val headers = acl.toOption.get.accessControl.staticContext.obfuscatedHeaders
        headers should have size 1
        headers.head should be(Header.Name(NonEmptyString.unsafeFrom("CorpoAuth")))
      }
    }
    "check policy" when {
      "allow policy set" in {
        val config = rorConfigFromUnsafe(
          """
            |readonlyrest:
            |
            |  access_control_rules:
            |
            |  - name: test_block1
            |    type: allow
            |    auth_key: admin:container
            |
            |  - name: test_block2
            |    type:
            |      policy: allow
            |    auth_key: test:test
            |
            |""".stripMargin)
        inside(createCore(config)) {
          case Right(Core(acl: EnabledAccessControlList, _)) =>
            val firstBlock = acl.blocks.head
            firstBlock.name should be(Block.Name("test_block1"))
            firstBlock.policy should be(Block.Policy.Allow)
            firstBlock.verbosity should be(Block.Verbosity.Info)
            firstBlock.rules should have size 1

            val secondBlock = acl.blocks.tail.head
            secondBlock.name should be(Block.Name("test_block2"))
            secondBlock.policy should be(Block.Policy.Allow)
            secondBlock.verbosity should be(Block.Verbosity.Info)
            secondBlock.rules should have size 1
        }
      }
      "forbid policy set" in {
        val config = rorConfigFromUnsafe(
          """
            |readonlyrest:
            |
            |  access_control_rules:
            |
            |  - name: test_block1
            |    type: forbid
            |    auth_key: admin:container
            |
            |  - name: test_block2
            |    type:
            |      policy: forbid
            |    auth_key: test:test
            |
            |  - name: test_block3
            |    type:
            |      policy: forbid
            |      response_message: "you are unauthorized to access this resource"
            |    auth_key: test:test
            |
            |""".stripMargin)
        inside(createCore(config)) {
          case Right(Core(acl: EnabledAccessControlList, _)) =>
            val firstBlock = acl.blocks.head
            firstBlock.name should be(Block.Name("test_block1"))
            firstBlock.policy should be(Block.Policy.Forbid(None))
            firstBlock.verbosity should be(Block.Verbosity.Info)
            firstBlock.rules should have size 1

            val secondBlock = acl.blocks.tail.head
            secondBlock.name should be(Block.Name("test_block2"))
            secondBlock.policy should be(Block.Policy.Forbid(None))
            secondBlock.verbosity should be(Block.Verbosity.Info)
            secondBlock.rules should have size 1

            val thirdBlock = acl.blocks.tail(1)
            thirdBlock.name should be(Block.Name("test_block3"))
            thirdBlock.policy should be(Block.Policy.Forbid(Some("you are unauthorized to access this resource")))
            thirdBlock.verbosity should be(Block.Verbosity.Info)
            thirdBlock.rules should have size 1
        }
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
        acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(Message(s"Blocks must have unique names. Duplicates: test_block, test_block2")))))
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
        acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(MalformedValue.fromString(
          """type: "allow"
            |auth_key: "admin:container"
            |""".stripMargin
        )))))
      }
      "block has unknown policy type" when {
        "simple policy format" in {
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
          acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(Message("Unknown block policy type: unknown. Supported types: 'allow'(default), 'forbid'.")))))
        }
        "extended policy format" in {
          val config = rorConfigFromUnsafe(
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block
              |    type:
              |      policy: unknown
              |    auth_key: admin:container
              |
              |""".stripMargin)
          val acl = createCore(config)
          acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(Message("Unknown block policy type: unknown. Supported types: 'allow'(default), 'forbid'.")))))

        }
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
        acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(Message("Unknown verbosity value: unknown. Supported types: 'info'(default), 'error'.")))))
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
            |    response_group_ids_json_path: "$..groups[?(@.id)].id"
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
        acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(Message("The 'test_block' block should contain only one authentication rule, but contains: [auth_key, proxy_auth]")))))
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
        acl should be(Left(NonEmptyList.one(RulesLevelCreationError(Message("Unknown rules: unknown_rule1, unknown_rule2")))))
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
        case Right(Core(acl: EnabledAccessControlList, _)) =>
          val firstBlock = acl.blocks.head
          firstBlock.name should be(Block.Name("test_block1"))
          firstBlock.policy should be(Block.Policy.Forbid(None))
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
          case Right(Core(acl: EnabledAccessControlList, _)) =>
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
