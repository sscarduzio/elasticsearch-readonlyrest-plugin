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
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.{BlocksLevelCreationError, DefinitionsLevelCreationError, RulesLevelCreationError, UnparsableYamlContent}
import tech.beshu.ror.acl.SequentialAcl
import tech.beshu.ror.acl.blocks.Block
import tech.beshu.ror.acl.factory.HttpClientsFactory.HttpClient
import tech.beshu.ror.acl.factory.{CoreSettings, CoreFactory}
import tech.beshu.ror.acl.utils.{JavaEnvVarsProvider, JavaUuidProvider, StaticVariablesResolver, UuidProvider}
import tech.beshu.ror.mocks.{MockHttpClientsFactory, MockHttpClientsFactoryWithFixedHttpClient}
import monix.execution.Scheduler.Implicits.global

class CoreFactoryTests extends WordSpec with Inside with MockFactory {

  private val factory = {
    implicit val clock: Clock = Clock.systemUTC()
    implicit val uuidProvider: UuidProvider = JavaUuidProvider
    implicit val resolver: StaticVariablesResolver = new StaticVariablesResolver(JavaEnvVarsProvider)
    new CoreFactory
  }

  "A RorAclFactory" should {
    "return unparsable content error" when {
      "config is not valid yaml" in {
        val yaml =
          """
            |readonlyrest:
            |
            |  access_control_rules:
            |
            |  - name: "CONTAINER ADMIN"
            |     - name1: "CONTAINER ADMIN"
            |    type: allow
          """.stripMargin
        val acl = factory.createCoreFrom(yaml, MockHttpClientsFactory).runSyncUnsafe()
        acl should be(Left(NonEmptyList.one(UnparsableYamlContent(Message(s"Malformed: $yaml")))))
      }
    }
    "return proxy auth configs error" when {
      "the section exists, but not contain any element" in {
        val yaml =
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
            |""".stripMargin
        val acl = factory.createCoreFrom(yaml, MockHttpClientsFactory).runSyncUnsafe()
        acl should be(Left(NonEmptyList.one(DefinitionsLevelCreationError(Message("proxy_auth_configs declared, but no definition found")))))
      }
      "the section contains proxies with the same names" in {
        val yaml =
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
            |""".stripMargin
        val acl = factory.createCoreFrom(yaml, MockHttpClientsFactory).runSyncUnsafe()
        acl should be(Left(NonEmptyList.one(DefinitionsLevelCreationError(Message("proxy_auth_configs definitions must have unique identifiers. Duplicates: proxy1")))))
      }
      "proxy definition has no name" in {
        val yaml =
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
            |""".stripMargin
        val acl = factory.createCoreFrom(yaml, MockHttpClientsFactory).runSyncUnsafe()
        acl should be(Left(NonEmptyList.one(DefinitionsLevelCreationError(MalformedValue(
          """desc: "proxy1"
            |user_id_header: "X-Auth-Token2"
            |""".stripMargin
        )))))
      }
      "proxy definition has no user id" in {
        val yaml =
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
            |""".stripMargin
        val acl = factory.createCoreFrom(yaml, MockHttpClientsFactory).runSyncUnsafe()
        acl should be(Left(NonEmptyList.one(DefinitionsLevelCreationError(MalformedValue("name: \"proxy1\"\n")))))
      }
    }
    "return blocks level error" when {
      "there is no `access_control_rules` section" in {
        val yaml =
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
            |""".stripMargin
        val acl = factory.createCoreFrom(yaml, MockHttpClientsFactory).runSyncUnsafe()
        acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(Message(s"No access_control_rules section found")))))
      }
      "there is `access_control_rules` section defined, but without any block" in {
        val yaml =
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
            |""".stripMargin
        val acl = factory.createCoreFrom(yaml, MockHttpClientsFactory).runSyncUnsafe()
        acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(Message(s"access_control_rules defined, but no block found")))))
      }
      "two blocks has the same names" in {
        val yaml =
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
            |""".stripMargin
        val acl = factory.createCoreFrom(yaml, MockHttpClientsFactory).runSyncUnsafe()
        acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(Message(s"Blocks must have unique names. Duplicates: test_block")))))
      }
      "block has no name" in {
        val yaml =
          """
            |readonlyrest:
            |
            |  access_control_rules:
            |
            |  - type: allow
            |    auth_key: admin:container
            |
            |""".stripMargin
        val acl = factory.createCoreFrom(yaml, MockHttpClientsFactory).runSyncUnsafe()
        acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(MalformedValue(
          """type: "allow"
            |auth_key: "admin:container"
            |""".stripMargin
        )))))
      }
      "block has unknown type" in {
        val yaml =
          """
            |readonlyrest:
            |
            |  access_control_rules:
            |
            |  - name: test_block
            |    type: unknown
            |    auth_key: admin:container
            |
            |""".stripMargin
        val acl = factory.createCoreFrom(yaml, MockHttpClientsFactory).runSyncUnsafe()
        acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(Message("Unknown block policy type: unknown")))))
      }
      "block has unknown verbosity" in {
        val yaml =
          """
            |readonlyrest:
            |
            |  access_control_rules:
            |
            |  - name: test_block
            |    verbosity: unknown
            |    auth_key: admin:container
            |
            |""".stripMargin
        val acl = factory.createCoreFrom(yaml, MockHttpClientsFactory).runSyncUnsafe()
        acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(Message("Unknown verbosity value: unknown")))))
      }
      "block has authorization rule, but no authentication rule" in {
        val yaml =
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
            |""".stripMargin
        val acl = factory.createCoreFrom(yaml, new MockHttpClientsFactoryWithFixedHttpClient(mock[HttpClient])).runSyncUnsafe()
        acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(Message("The 'test_block' block contains an authorization rule, but not an authentication rule. This does not mean anything if you don't also set some authentication rule.")))))
      }
    }
    "return rule level error" when {
      "no rules are defined in block" in {
        val yaml =
          """
            |readonlyrest:
            |
            |  access_control_rules:
            |
            |  - name: test_block
            |    type: allow
            |
            |""".stripMargin
        val acl = factory.createCoreFrom(yaml, MockHttpClientsFactory).runSyncUnsafe()
        acl should be(Left(NonEmptyList.one(RulesLevelCreationError(Message("No rules defined in block")))))
      }
      "block has unknown rules" in {
        val yaml =
          """
            |readonlyrest:
            |
            |  access_control_rules:
            |
            |  - name: test_block
            |    unknown_rule1: value2
            |    unknown_rule2: value1
            |
            |""".stripMargin
        val acl = factory.createCoreFrom(yaml, MockHttpClientsFactory).runSyncUnsafe()
        acl should be(Left(NonEmptyList.one(RulesLevelCreationError(Message("Unknown rules: unknown_rule1,unknown_rule2")))))
      }
    }
    "return ACL with blocks defined in config" in {
      val yaml =
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
          |""".stripMargin

      inside(factory.createCoreFrom(yaml, MockHttpClientsFactory).runSyncUnsafe()) {
        case Right(CoreSettings(acl: SequentialAcl, _, _)) =>
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
  }
}
