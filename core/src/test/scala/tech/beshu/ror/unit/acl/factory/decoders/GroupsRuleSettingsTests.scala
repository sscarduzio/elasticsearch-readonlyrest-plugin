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
package tech.beshu.ror.unit.acl.factory.decoders

import cats.data.NonEmptySet
import org.scalatest.Inside
import org.scalatest.Matchers._
import tech.beshu.ror.acl.blocks.definitions.UserDef
import tech.beshu.ror.acl.blocks.rules.{AuthKeyRule, AuthKeySha1Rule, BasicAuthenticationRule, GroupsRule}
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeMultiResolvableVariable.{AlreadyResolved, ToBeResolved}
import tech.beshu.ror.acl.domain.{Group, Secret, User}
import tech.beshu.ror.acl.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.acl.factory.RawRorConfigBasedCoreFactory.AclCreationError.{DefinitionsLevelCreationError, RulesLevelCreationError}
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.utils.TestsUtils._

class GroupsRuleSettingsTests extends BaseRuleSettingsDecoderTest[GroupsRule] with Inside {

  "A GroupsRule" should {
    "be able to be loaded from config" when {
      "only one group is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
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
            val groups: NonEmptySet[RuntimeMultiResolvableVariable[Group]] = NonEmptySet.one(AlreadyResolved(groupFrom("group1").nel))
            rule.settings.groups should be(groups)
            rule.settings.usersDefinitions.length should be(1)
            inside(rule.settings.usersDefinitions.head) { case UserDef(name, userGroups, authRule) =>
              name should be(User.Id("cartman"))
              userGroups should be(NonEmptySet.of(groupFrom("group1"), groupFrom("group3")))
              authRule shouldBe an[AuthKeyRule]
              authRule.asInstanceOf[AuthKeyRule].settings should be(BasicAuthenticationRule.Settings(Secret("cartman:pass")))
            }
          }
        )
      }
      "several groups are defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
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
            val groups: NonEmptySet[RuntimeMultiResolvableVariable[Group]] =
              NonEmptySet.of(AlreadyResolved(groupFrom("group1").nel), AlreadyResolved(groupFrom("group2").nel))
            rule.settings.groups should be(groups)
            rule.settings.usersDefinitions.length should be(2)
            val sortedUserDefinitions = rule.settings.usersDefinitions.toSortedSet
            inside(sortedUserDefinitions.head) { case UserDef(name, userGroups, authRule) =>
              name should be(User.Id("cartman"))
              userGroups should be(NonEmptySet.of(groupFrom("group1"), groupFrom("group3")))
              authRule shouldBe an[AuthKeyRule]
              authRule.asInstanceOf[AuthKeyRule].settings should be(BasicAuthenticationRule.Settings(Secret("cartman:pass")))
            }
            inside(sortedUserDefinitions.tail.head) { case UserDef(name, userGroups, authRule) =>
              name should be(User.Id("morgan"))
              userGroups should be(NonEmptySet.of(groupFrom("group2"), groupFrom("group3")))
              authRule shouldBe an[AuthKeySha1Rule]
              authRule.asInstanceOf[AuthKeySha1Rule].settings should be(BasicAuthenticationRule.Settings(Secret("d27aaf7fa3c1603948bb29b7339f2559dc02019a")))
            }
          }
        )
      }
      "several groups are defined with variable placed for one of them" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
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
            rule.settings.groups.toSortedSet.size shouldBe 2
            rule.settings.groups.toSortedSet.head should be(AlreadyResolved(groupFrom("group1").nel))
            rule.settings.groups.toSortedSet.tail.head shouldBe a [ToBeResolved[_]]

            rule.settings.usersDefinitions.length should be(1)
            inside(rule.settings.usersDefinitions.head) { case UserDef(name, userGroups, authRule) =>
              name should be(User.Id("cartman"))
              userGroups should be(NonEmptySet.of(groupFrom("group1"), groupFrom("group3")))
              authRule shouldBe an[AuthKeyRule]
              authRule.asInstanceOf[AuthKeyRule].settings should be(BasicAuthenticationRule.Settings(Secret("cartman:pass")))
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
              |readonlyrest:
              |
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
              """groups: null
                |""".stripMargin
            )))
          }
        )
      }
      "no users definitions section is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
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
              |readonlyrest:
              |
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
            errors.head should be(DefinitionsLevelCreationError(Message("User definition malformed")))
          }
        )
      }
      "username filed is absent in user definition" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
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
            errors.head should be(DefinitionsLevelCreationError(Message("User definition malformed")))
          }
        )
      }
      "groups set in user definitions is not defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
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
            errors.head should be(DefinitionsLevelCreationError(Message("Non empty list of groups are required")))
          }
        )
      }
      "groups set in user definitions is empty" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
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
            errors.head should be(DefinitionsLevelCreationError(Message("Non empty list of groups are required")))
          }
        )
      }
      "only one authentication rule can be defined for user in users section" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
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
            errors.head should be(DefinitionsLevelCreationError(Message("Only one authentication should be defined for user ['cartman']. Found auth_key, auth_key_sha1")))
          }
        )
      }
      "auth key rule inside user definition is unparsable" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
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
            errors.head should be(DefinitionsLevelCreationError(Message("Cannot parse 'auth_key' rule declared in user 'cartman' definition")))
          }
        )
      }
      "users inside users sections must have unique usernames" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
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
            errors.head should be(DefinitionsLevelCreationError(Message("users definitions must have unique identifiers. Duplicates: cartman")))
          }
        )
      }
      "user definition doesn't allow to define other fields than 'username', 'groups' and one auth rule" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
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
            errors.head should be(DefinitionsLevelCreationError(Message("Only one authentication should be defined for user ['cartman']. Found unknown_field, auth_key")))
          }
        )
      }
    }
  }
}
