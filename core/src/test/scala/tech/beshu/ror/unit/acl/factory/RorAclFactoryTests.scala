package tech.beshu.ror.unit.acl.factory

import cats.data.NonEmptyList
import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.{BlocksLevelCreationError, DefinitionsCreationError, RulesLevelCreationError, UnparsableYamlContent}
import tech.beshu.ror.TestsUtils._
import tech.beshu.ror.acl.SequentialAcl
import tech.beshu.ror.acl.blocks.Block
import tech.beshu.ror.acl.factory.RorAclFactory

class RorAclFactoryTests extends WordSpec with Inside {

  private val factory = new RorAclFactory()

  "A RorAclFactory" should {
    "return unparsable content error" when {
      "config is not valid yaml" in {
        val yaml =
          """
            |  access_control_rules:
            |
            |  - name: "CONTAINER ADMIN"
            |     - name1: "CONTAINER ADMIN"
            |    type: allow
          """.stripMargin
        val acl = factory.createAclFrom(yaml)
        acl should be(Left(NonEmptyList.one(UnparsableYamlContent(Message(s"Malformed: $yaml")))))
      }
    }
    "return proxy auth configs error" when {
      "the section exists, but not contain any element" in {
        val yaml =
          """
            |  access_control_rules:
            |
            |  - name: test_block
            |    type: allow
            |    auth_key: admin:container
            |
            |  proxy_auth_configs:
            |
            |""".stripMargin
        val acl = factory.createAclFrom(yaml)
        acl should be(Left(NonEmptyList.one(DefinitionsCreationError(Message("Proxy auth definitions declared, but no definition found")))))
      }
      "the section contains proxies with the same names" in {
        val yaml =
          """
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
        val acl = factory.createAclFrom(yaml)
        acl should be(Left(NonEmptyList.one(DefinitionsCreationError(Message("Proxy auth definitions must have unique names. Duplicates: proxy1")))))
      }
      "proxy definition has no name" in {
        val yaml =
          """
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
        val acl = factory.createAclFrom(yaml)
        acl should be(Left(NonEmptyList.one(DefinitionsCreationError(MalformedValue(
          jsonFrom(
            """{
              |  "desc" : "proxy1",
              |  "user_id_header" : "X-Auth-Token2"
              |}""".stripMargin
          ))))))
      }
      "proxy definition has no user id" in {
        val yaml =
          """
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
        val acl = factory.createAclFrom(yaml)
        acl should be(Left(NonEmptyList.one(DefinitionsCreationError(MalformedValue(
          jsonFrom(
            """{
              |  "name" : "proxy1"
              |}""".stripMargin
          ))))))
      }
    }
    "return blocks level error" when {
      "there is no `access_control_rules` section" in {
        val yaml =
          """
            |  proxy_auth_configs:
            |
            |  - name: "proxy1"
            |    user_id_header: "X-Auth-Token2"
            |
            |  - name: "proxy2"
            |    user_id_header: "X-Auth-Token1"
            |
            |""".stripMargin
        val acl = factory.createAclFrom(yaml)
        acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(Message(s"No access_control_rules section found")))))
      }
      "there is `access_control_rules` section defined, but without any block" in {
        val yaml =
          """
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
        val acl = factory.createAclFrom(yaml)
        acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(Message(s"access_control_rules defined, but no block found")))))
      }
      "two blocks has the same names" in {
        val yaml =
          """
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
        val acl = factory.createAclFrom(yaml)
        acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(Message(s"Blocks must have unique names. Duplicates: test_block")))))
      }
      "block has no name" in {
        val yaml =
          """
            |  access_control_rules:
            |
            |  - type: allow
            |    auth_key: admin:container
            |
            |""".stripMargin
        val acl = factory.createAclFrom(yaml)
        acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(MalformedValue(
          jsonFrom(
            """{
              |  "type" : "allow",
              |  "auth_key" : "admin:container"
              |}""".stripMargin)
        )))))
      }
      "block has unknown type" in {
        val yaml =
          """
            |  access_control_rules:
            |
            |  - name: test_block
            |    type: unknown
            |    auth_key: admin:container
            |
            |""".stripMargin
        val acl = factory.createAclFrom(yaml)
        acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(Message("Unknown block policy type: unknown")))))
      }
      "block has unknown verbosity" in {
        val yaml =
          """
            |  access_control_rules:
            |
            |  - name: test_block
            |    verbosity: unknown
            |    auth_key: admin:container
            |
            |""".stripMargin
        val acl = factory.createAclFrom(yaml)
        acl should be(Left(NonEmptyList.one(BlocksLevelCreationError(Message("Unknown verbosity value: unknown")))))
      }
    }
    "return rule level error" when {
      "no rules are defined in block" in {
        val yaml =
          """
            |  access_control_rules:
            |
            |  - name: test_block
            |    type: allow
            |
            |""".stripMargin
        val acl = factory.createAclFrom(yaml)
        acl should be(Left(NonEmptyList.one(RulesLevelCreationError(Message("No rules defined in block")))))
      }
      "block has unknown rules" in {
        val yaml =
          """
            |  access_control_rules:
            |
            |  - name: test_block
            |    unknown_rule1: value2
            |    unknown_rule2: value1
            |
            |""".stripMargin
        val acl = factory.createAclFrom(yaml)
        acl should be(Left(NonEmptyList.one(RulesLevelCreationError(Message("Unknown rules: unknown_rule1,unknown_rule2")))))
      }
    }
    "return ACL with blocks defined in config" in {
      val yaml =
        """
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

      inside(factory.createAclFrom(yaml)) { case Right(acl: SequentialAcl) =>
        val firstBlock = acl.blocks.head
        firstBlock.name should be (Block.Name("test_block1"))
        firstBlock.policy should be (Block.Policy.Forbid)
        firstBlock.verbosity should be (Block.Verbosity.Info)
        firstBlock.rules should have size 1

        val secondBlock = acl.blocks.tail.head
        secondBlock.name should be (Block.Name("test_block2"))
        secondBlock.policy should be (Block.Policy.Allow)
        secondBlock.verbosity should be (Block.Verbosity.Error)
        secondBlock.rules should have size 1
      }
    }
  }
}
