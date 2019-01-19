package tech.beshu.ror.unit.acl.factory.decoders

import cats.data.NonEmptySet
import org.scalatest.Inside
import org.scalatest.Matchers._
import tech.beshu.ror.TestsUtils.jsonFrom
import tech.beshu.ror.acl.aDomain.{AuthData, Group}
import tech.beshu.ror.acl.blocks.Value._
import tech.beshu.ror.acl.blocks.Variable.ValueWithVariable
import tech.beshu.ror.acl.blocks.definitions.UserDef
import tech.beshu.ror.acl.blocks.rules.{AuthKeyRule, AuthKeySha1Rule, BasicAuthenticationRule, GroupsRule}
import tech.beshu.ror.acl.blocks.{Const, Value, Variable}
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.{DefinitionsCreationError, RulesLevelCreationError}
import tech.beshu.ror.acl.orders._

class GroupsRuleSettingsTests extends RuleSettingsDecoderTest[GroupsRule] with Inside {

  "A GroupsRule" should {
    "be able to be loaded from config" when {
      "only one group is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups: group1
              |
              |  users:
              |  - username: cartman
              |    groups: ["group1", "group3"]
              |    auth_key: "cartman:pass"
              |
              |""".stripMargin,
          assertion = rule => {
            val groups: NonEmptySet[Value[Group]] = NonEmptySet.one(Const(Group("group1")))
            rule.settings.groups should be(groups)
            rule.settings.usersDefinitions.users.toList should have length 1
            inside(rule.settings.usersDefinitions.users.head) { case UserDef(name, userGroups, authRule) =>
              name should be(UserDef.Name("cartman"))
              userGroups should be(NonEmptySet.of(Group("group1"), Group("group3")))
              authRule shouldBe an[AuthKeyRule]
              authRule.asInstanceOf[AuthKeyRule].settings should be (BasicAuthenticationRule.Settings(AuthData("cartman:pass")))
            }
          }
        )
      }
      "several groups are defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups: [group1, group2]
              |
              |  users:
              |  - username: cartman
              |    groups: ["group1", "group3"]
              |    auth_key: "cartman:pass"
              |
              |  - username: morgan
              |    groups: ["group2", "group3"]
              |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
              |
              |""".stripMargin,
          assertion = rule => {
            val groups: NonEmptySet[Value[Group]] = NonEmptySet.of(Const(Group("group1")), Const(Group("group2")))
            rule.settings.groups should be(groups)
            rule.settings.usersDefinitions.users.toList should have length 2
            inside(rule.settings.usersDefinitions.users.head) { case UserDef(name, userGroups, authRule) =>
              name should be(UserDef.Name("cartman"))
              userGroups should be(NonEmptySet.of(Group("group1"), Group("group3")))
              authRule shouldBe an[AuthKeyRule]
              authRule.asInstanceOf[AuthKeyRule].settings should be (BasicAuthenticationRule.Settings(AuthData("cartman:pass")))
            }
            inside(rule.settings.usersDefinitions.users.tail.head) { case UserDef(name, userGroups, authRule) =>
              name should be(UserDef.Name("morgan"))
              userGroups should be(NonEmptySet.of(Group("group2"), Group("group3")))
              authRule shouldBe an[AuthKeySha1Rule]
              authRule.asInstanceOf[AuthKeySha1Rule].settings should be (BasicAuthenticationRule.Settings(AuthData("d27aaf7fa3c1603948bb29b7339f2559dc02019a")))
            }
          }
        )
      }
      "several groups are defined with variable placed for one of them" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups: [group1, "group_@{user}"]
              |
              |  users:
              |  - username: cartman
              |    groups: ["group1", "group3"]
              |    auth_key: "cartman:pass"
              |
              |""".stripMargin,
          assertion = rule => {
            val groups: NonEmptySet[Value[Group]] = NonEmptySet.of(
              Const(Group("group1")),
              Variable(ValueWithVariable("group_@{user}"), rv => Right(Group(rv.value)))
            )
            rule.settings.groups should be(groups)
            rule.settings.usersDefinitions.users.toList should have length 1
            inside(rule.settings.usersDefinitions.users.head) { case UserDef(name, userGroups, authRule) =>
              name should be(UserDef.Name("cartman"))
              userGroups should be(NonEmptySet.of(Group("group1"), Group("group3")))
              authRule shouldBe an[AuthKeyRule]
              authRule.asInstanceOf[AuthKeyRule].settings should be (BasicAuthenticationRule.Settings(AuthData("cartman:pass")))
            }
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "groups section is defined, but without any group" in {
        assertDecodingFailure(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups:
              |
              |  users:
              |  - username: cartman
              |    groups: ["group1", "group3"]
              |    auth_key: "cartman:pass"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue(
              jsonFrom(
                """[
                  |  {
                  |    "groups" : null
                  |  }
                  |]""".stripMargin)
            )))
          }
        )
      }
      "no users definitions section is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups: group1
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message("No user definitions was defined. Rule `groups` requires them.")))
          }
        )
      }
      "username in user definitions is empty" in {
        assertDecodingFailure(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups: group1
              |
              |  users:
              |  - username:
              |    groups: ["group1", "group3"]
              |    auth_key: "cartman:pass"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsCreationError(Message("User definition malformed")))
          }
        )
      }
      "username filed is absent in user definition" in {
        assertDecodingFailure(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups: group1
              |
              |  users:
              |  - groups: ["group1", "group3"]
              |    auth_key: "cartman:pass"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsCreationError(Message("User definition malformed")))
          }
        )
      }
      "groups set in user definitions is not defined" in {
        assertDecodingFailure(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups:
              |
              |  users:
              |  - username: cartman
              |    groups:
              |    auth_key: "cartman:pass"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsCreationError(Message("Non empty list of groups are required")))
          }
        )
      }
      "groups set in user definitions is empty" in {
        assertDecodingFailure(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups:
              |
              |  users:
              |  - username: cartman
              |    groups: []
              |    auth_key: "cartman:pass"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsCreationError(Message("Non empty list of groups are required")))
          }
        )
      }
      "only one authentication rule can be defined for user in users section" in {
        assertDecodingFailure(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups:
              |
              |  users:
              |  - username: cartman
              |    groups: ["group1", "group3"]
              |    auth_key: "cartman:pass"
              |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsCreationError(Message("Only one authentication should be defined for user ['cartman']. Found auth_key, auth_key_sha1")))
          }
        )
      }
      "auth key rule inside user definition is unparsable" in {
        assertDecodingFailure(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups: group1
              |
              |  users:
              |  - username: cartman
              |    groups: ["group1", "group3"]
              |    auth_key:
              |      key: "cartman:pass"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsCreationError(Message("Cannot parse 'auth_key' rule declared in user 'cartman' definition")))
          }
        )
      }
      "users inside users sections must have unique usernames" in {
        assertDecodingFailure(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups:
              |
              |  users:
              |  - username: cartman
              |    groups: ["group1", "group3"]
              |    auth_key: "cartman:pass"
              |
              |  - username: cartman
              |    groups: ["group2", "group3"]
              |    auth_key: "cartman:pass"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsCreationError(Message("User definitions must have unique names. Duplicates: cartman")))
          }
        )
      }
      "user definition doesn't allow to define other fields than 'username', 'groups' and one auth rule" in {
        assertDecodingFailure(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups:
              |
              |  users:
              |  - username: cartman
              |    groups: ["group1", "group3"]
              |    unknown_field: "abc"
              |    auth_key: "cartman:pass"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsCreationError(Message("Only one authentication should be defined for user ['cartman']. Found unknown_field, auth_key")))
          }
        )
      }
    }
  }
}
