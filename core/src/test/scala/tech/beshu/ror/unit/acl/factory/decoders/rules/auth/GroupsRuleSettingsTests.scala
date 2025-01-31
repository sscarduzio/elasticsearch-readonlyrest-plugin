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
package tech.beshu.ror.unit.acl.factory.decoders.rules.auth

import cats.data.NonEmptyList
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.GroupMappings
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.GroupMappings.Advanced.Mapping
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.Mode.WithGroupsMapping.Auth
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.Mode.{WithGroupsMapping, WithoutGroupsMapping}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleName
import tech.beshu.ror.accesscontrol.blocks.rules.auth.*
import tech.beshu.ror.accesscontrol.blocks.rules.auth.AuthKeyHashingRule.HashedCredentials.HashedUserAndPassword
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BasicAuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.{AlreadyResolved, ToBeResolved}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.GroupsLogic.GroupsLogicResolver
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.{DefinitionsLevelCreationError, RulesLevelCreationError}
import tech.beshu.ror.unit.acl.factory.decoders.rules.BaseRuleSettingsDecoderTest
import tech.beshu.ror.utils.SingletonLdapContainers
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.reflect.ClassTag

class GroupsOrRuleSettingsTests extends GroupsRuleSettingsTests(GroupsOrRule.Name) {
  override def createPermittedGroupsLogicResolver(groupIds: ResolvableGroupIds): GroupsLogicResolver = GroupsLogicResolver(groupIds, GroupsLogic.Or.apply)
}

class DeprecatedGroupsOrRuleSettingsTests extends GroupsRuleSettingsTests(GroupsOrRule.DeprecatedName) {
  override def createPermittedGroupsLogicResolver(groupIds: ResolvableGroupIds): GroupsLogicResolver = GroupsLogicResolver(groupIds, GroupsLogic.Or.apply)
}

class GroupsAndRuleSettingsTests extends GroupsRuleSettingsTests[GroupsAndRule](GroupsAndRule.Name) {
  override def createPermittedGroupsLogicResolver(groupIds: ResolvableGroupIds): GroupsLogicResolver = GroupsLogicResolver(groupIds, GroupsLogic.And.apply)
}

class GroupsNotAllOfRuleSettingsTests extends GroupsRuleSettingsTests[GroupsNotAllOfRule](GroupsNotAllOfRule.Name) {
  override def createPermittedGroupsLogicResolver(groupIds: ResolvableGroupIds): GroupsLogicResolver = GroupsLogicResolver(groupIds, GroupsLogic.NotAllOf.apply)
}

class GroupsNotAnyOfRuleSettingsTests extends GroupsRuleSettingsTests[GroupsNotAnyOfRule](GroupsNotAnyOfRule.Name) {
  override def createPermittedGroupsLogicResolver(groupIds: ResolvableGroupIds): GroupsLogicResolver = GroupsLogicResolver(groupIds, GroupsLogic.NotAnyOf.apply)
}

sealed abstract class GroupsRuleSettingsTests[R <: BaseGroupsRule : ClassTag](ruleName: RuleName[R])
  extends BaseRuleSettingsDecoderTest[R]
    with Inside {

  def createPermittedGroupsLogicResolver(groupIds: ResolvableGroupIds): GroupsLogicResolver

  "A GroupsOrRule" should {
    "be able to be loaded from config" when {
      "a groups mapping is not used" when {
        "only one group is defined" when {
          "one, full username is used" in {
            assertDecodingSuccess(
              yaml =
                s"""
                   |readonlyrest:
                   |
                   |  access_control_rules:
                   |
                   |  - name: test_block1
                   |    ${ruleName.name.value}: group1
                   |
                   |  users:
                   |  - username: cartman
                   |    groups: ["group1", "group3"]
                   |    auth_key: "cartman:pass"
                   |
                   |""".stripMargin,
              assertion = rule => {
                val groups = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("group1").nel)))
                rule.settings.permittedGroupsLogic should be(createPermittedGroupsLogicResolver(groups))
                rule.settings.usersDefinitions.length should be(1)
                inside(rule.settings.usersDefinitions.head) { case UserDef(_, patterns, WithoutGroupsMapping(authRule, localGroups)) =>
                  patterns should be(UserIdPatterns(UniqueNonEmptyList.of(User.UserIdPattern(userId("cartman")))))
                  localGroups should be(UniqueNonEmptyList.of(group("group1"), group("group3")))
                  authRule shouldBe an[AuthKeyRule]
                  authRule.asInstanceOf[AuthKeyRule].settings should be {
                    BasicAuthenticationRule.Settings(Credentials(userId("cartman"), PlainTextSecret("pass")))
                  }
                }
              }
            )
          }
          "one, username with wildcard is used" in {
            assertDecodingSuccess(
              yaml =
                s"""
                   |readonlyrest:
                   |
                   |  access_control_rules:
                   |
                   |  - name: test_block1
                   |    ${ruleName.name.value}: group1
                   |
                   |  users:
                   |  - username: car*
                   |    groups: ["group1", "group3"]
                   |    auth_key: "cartman:pass"
                   |
                   |""".stripMargin,
              assertion = rule => {
                val permittedGroups = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("group1").nel)))
                rule.settings.permittedGroupsLogic should be(createPermittedGroupsLogicResolver(permittedGroups))
                rule.settings.usersDefinitions.length should be(1)
                inside(rule.settings.usersDefinitions.head) { case UserDef(_, patterns, WithoutGroupsMapping(authRule, localGroups)) =>
                  patterns should be(UserIdPatterns(UniqueNonEmptyList.of(User.UserIdPattern(userId("car*")))))
                  localGroups should be(UniqueNonEmptyList.of(group("group1"), group("group3")))
                  authRule shouldBe an[AuthKeyRule]
                  authRule.asInstanceOf[AuthKeyRule].settings should be {
                    BasicAuthenticationRule.Settings(Credentials(userId("cartman"), PlainTextSecret("pass")))
                  }
                }
              }
            )
          }
          "two usernames are used (the first one - full, the second - with wildcard)" in {
            assertDecodingSuccess(
              yaml =
                s"""
                   |readonlyrest:
                   |
                   |  access_control_rules:
                   |
                   |  - name: test_block1
                   |    ${ruleName.name.value}: group1
                   |
                   |  users:
                   |  - username: [cartman, "ca*"]
                   |    groups: "group1"
                   |    auth_key: "cartman:pass"
                   |
                   |""".stripMargin,
              assertion = rule => {
                val groups = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("group1").nel)))
                rule.settings.permittedGroupsLogic should be(createPermittedGroupsLogicResolver(groups))
                rule.settings.usersDefinitions.length should be(1)
                inside(rule.settings.usersDefinitions.head) { case UserDef(_, patterns, WithoutGroupsMapping(authRule, localGroups)) =>
                  patterns should be(UserIdPatterns(UniqueNonEmptyList.of(User.UserIdPattern(userId("cartman")), User.UserIdPattern(userId("ca*")))))
                  localGroups should be(UniqueNonEmptyList.of(group("group1")))
                  authRule shouldBe an[AuthKeyRule]
                  authRule.asInstanceOf[AuthKeyRule].settings should be {
                    BasicAuthenticationRule.Settings(Credentials(userId("cartman"), PlainTextSecret("pass")))
                  }
                }
              }
            )
          }
          "structured groups format is used" in {
            assertDecodingSuccess(
              yaml =
                s"""
                   |readonlyrest:
                   |
                   |  access_control_rules:
                   |
                   |  - name: test_block1
                   |    ${ruleName.name.value}: group1
                   |
                   |  users:
                   |  - username: cartman
                   |    groups:
                   |    - id: "group1"
                   |      name: "Group 1"
                   |    - id: "group3"
                   |      name: "Group 3"
                   |    auth_key: "cartman:pass"
                   |
                   |""".stripMargin,
              assertion = rule => {
                val groups = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("group1").nel)))
                rule.settings.permittedGroupsLogic should be(createPermittedGroupsLogicResolver(groups))
                rule.settings.usersDefinitions.length should be(1)
                inside(rule.settings.usersDefinitions.head) { case UserDef(_, patterns, WithoutGroupsMapping(authRule, localGroups)) =>
                  patterns should be(UserIdPatterns(UniqueNonEmptyList.of(User.UserIdPattern(userId("cartman")))))
                  localGroups should be(UniqueNonEmptyList.of(group("group1", "Group 1"), group("group3", "Group 3")))
                  authRule shouldBe an[AuthKeyRule]
                  authRule.asInstanceOf[AuthKeyRule].settings should be {
                    BasicAuthenticationRule.Settings(Credentials(userId("cartman"), PlainTextSecret("pass")))
                  }
                }
              }
            )
          }
          "two usernames used" in {
            assertDecodingSuccess(
              yaml =
                s"""
                   |readonlyrest:
                   |
                   |  access_control_rules:
                   |
                   |  - name: test_block1
                   |    ${ruleName.name.value}: group1
                   |
                   |  users:
                   |  - username: cartman
                   |    groups: ["group1", "group3"]
                   |    auth_key: "cartman:pass"
                   |
                   |  - username: morgan
                   |    groups: ["group2", "group4"]
                   |    token_authentication:
                   |      token: "Bearer abc123XYZ"
                   |      username: "morgan"
                   |      header: "Authorization"
                   |
                   |""".stripMargin,
              assertion = rule => {
                val groups = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("group1").nel)))
                rule.settings.permittedGroupsLogic should be(createPermittedGroupsLogicResolver(groups))
                rule.settings.usersDefinitions.length should be(2)
                inside(rule.settings.usersDefinitions.head) { case UserDef(_, patterns, WithoutGroupsMapping(authRule, localGroups)) =>
                  patterns should be(UserIdPatterns(UniqueNonEmptyList.of(User.UserIdPattern(userId("cartman")))))
                  localGroups should be(UniqueNonEmptyList.of(group("group1"), group("group3")))
                  authRule shouldBe an[AuthKeyRule]
                  authRule.asInstanceOf[AuthKeyRule].settings should be {
                    BasicAuthenticationRule.Settings(Credentials(userId("cartman"), PlainTextSecret("pass")))
                  }
                }
                inside(rule.settings.usersDefinitions.tail.head) { case UserDef(_, patterns, WithoutGroupsMapping(authRule, localGroups)) =>
                  patterns should be(UserIdPatterns(UniqueNonEmptyList.of(User.UserIdPattern(userId("morgan")))))
                  localGroups should be(UniqueNonEmptyList.of(group("group2"), group("group4")))
                  authRule shouldBe an[TokenAuthenticationRule]
                  authRule.asInstanceOf[TokenAuthenticationRule].settings should be {
                    TokenAuthenticationRule.Settings(userId("morgan"), Token("Bearer abc123XYZ"), Header.Name.authorization)
                  }
                }
              }
            )
          }
        }
        "several groups are defined" when {
          "no variables are used in group ids" in {
            assertDecodingSuccess(
              yaml =
                s"""
                   |readonlyrest:
                   |
                   |  access_control_rules:
                   |
                   |  - name: test_block1
                   |    ${ruleName.name.value}: [group1, group2]
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
                val groups = ResolvableGroupIds(UniqueNonEmptyList.of(
                  AlreadyResolved(GroupId("group1").nel),
                  AlreadyResolved(GroupId("group2").nel)
                ))
                rule.settings.permittedGroupsLogic should be(createPermittedGroupsLogicResolver(groups))
                rule.settings.usersDefinitions.length should be(2)
                val sortedUserDefinitions = rule.settings.usersDefinitions
                inside(sortedUserDefinitions.head) { case UserDef(_, patterns, WithoutGroupsMapping(authRule, localGroups)) =>
                  patterns should be(UserIdPatterns(UniqueNonEmptyList.of(User.UserIdPattern(userId("cartman")))))
                  localGroups should be(UniqueNonEmptyList.of(group("group1"), group("group3")))
                  authRule shouldBe an[AuthKeyRule]
                  authRule.asInstanceOf[AuthKeyRule].settings should be {
                    BasicAuthenticationRule.Settings(Credentials(userId("cartman"), PlainTextSecret("pass")))
                  }
                }
                inside(sortedUserDefinitions.tail.head) { case UserDef(_, patterns, WithoutGroupsMapping(authRule, localGroups)) =>
                  patterns should be(UserIdPatterns(UniqueNonEmptyList.of(User.UserIdPattern(userId("morgan")))))
                  localGroups should be(UniqueNonEmptyList.of(group("group2"), group("group3")))
                  authRule shouldBe an[AuthKeySha1Rule]
                  authRule.asInstanceOf[AuthKeySha1Rule].settings should be {
                    BasicAuthenticationRule.Settings(HashedUserAndPassword("d27aaf7fa3c1603948bb29b7339f2559dc02019a"))
                  }
                }
              }
            )
          }
          "a variable in used in one group id" in {
            assertDecodingSuccess(
              yaml =
                s"""
                   |readonlyrest:
                   |
                   |  access_control_rules:
                   |
                   |  - name: test_block1
                   |    ${ruleName.name.value}: [group1, "group_@{header:test}"]
                   |
                   |  users:
                   |  - username: cartman
                   |    groups: ["group1", "group3"]
                   |    auth_key: "cartman:pass"
                   |
                   |""".stripMargin,
              assertion = rule => {
                rule.settings.permittedGroupsLogic.usedVariables.size shouldBe 2
                rule.settings.permittedGroupsLogic.usedVariables.head should be(AlreadyResolved(GroupId("group1").nel))
                rule.settings.permittedGroupsLogic.usedVariables.tail.head shouldBe a[ToBeResolved[_]]

                rule.settings.usersDefinitions.length should be(1)
                inside(rule.settings.usersDefinitions.head) { case UserDef(_, patterns, WithoutGroupsMapping(authRule, localGroups)) =>
                  patterns should be(UserIdPatterns(UniqueNonEmptyList.of(User.UserIdPattern(userId("cartman")))))
                  localGroups should be(UniqueNonEmptyList.of(group("group1"), group("group3")))
                  authRule shouldBe an[AuthKeyRule]
                  authRule.asInstanceOf[AuthKeyRule].settings should be {
                    BasicAuthenticationRule.Settings(Credentials(userId("cartman"), PlainTextSecret("pass")))
                  }
                }
              }
            )
          }
        }
      }
      "a groups mapping is used" when {
        "separate authentication and authorization rules are used for that" in {
          assertDecodingSuccess(
            yaml =
              s"""
                 |readonlyrest:
                 |
                 |  access_control_rules:
                 |
                 |  - name: test_block1
                 |    ${ruleName.name.value}: [group1*, group2]
                 |
                 |  users:
                 |  - username: cartman
                 |    groups: ["group1"]
                 |    auth_key: "cartman:pass"
                 |    groups_provider_authorization:
                 |      user_groups_provider: GroupsService1
                 |      groups_or: ["group3"]
                 |
                 |  - username: morgan
                 |    groups:
                 |    - id: group2
                 |      name: Group 2
                 |    - id: group3
                 |      name: Group 3
                 |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
                 |
                 |  ldaps:
                 |  - name: ldap1
                 |    host: ${SingletonLdapContainers.ldap1.ldapHost}
                 |    port: ${SingletonLdapContainers.ldap1.ldapPort}
                 |    ssl_enabled: false
                 |    users:
                 |      search_user_base_DN: "ou=People,dc=example,dc=com"
                 |    groups:
                 |      search_groups_base_DN: "ou=People,dc=example,dc=com"
                 |
                 |  user_groups_providers:
                 |  - name: GroupsService1
                 |    groups_endpoint: "http://localhost:8080/groups"
                 |    auth_token_name: "user"
                 |    auth_token_passed_as: QUERY_PARAM
                 |    response_group_ids_json_path: "$$..groups[?(@.id)].id"
                 |
                 |""".stripMargin,
            assertion = rule => {
              val groups = ResolvableGroupIds(UniqueNonEmptyList.of(
                AlreadyResolved(GroupIdLike.from("group1*").nel),
                AlreadyResolved(GroupId("group2").nel)
              ))
              rule.settings.permittedGroupsLogic should be(createPermittedGroupsLogicResolver(groups))
              rule.settings.usersDefinitions.length should be(2)
              val sortedUserDefinitions = rule.settings.usersDefinitions
              inside(sortedUserDefinitions.head) { case UserDef(_, patterns, WithGroupsMapping(Auth.SeparateRules(rule1, rule2), groupMappings)) =>
                patterns should be(UserIdPatterns(UniqueNonEmptyList.of(User.UserIdPattern(userId("cartman")))))
                groupMappings should be(GroupMappings.Simple(UniqueNonEmptyList.of(group("group1"))))

                rule1 shouldBe an[AuthKeyRule]
                rule1.asInstanceOf[AuthKeyRule].settings should be {
                  BasicAuthenticationRule.Settings(Credentials(userId("cartman"), PlainTextSecret("pass")))
                }
                rule2 shouldBe an[ExternalAuthorizationRule]
                rule2.asInstanceOf[ExternalAuthorizationRule].settings.permittedGroupsLogic should be(
                  GroupsLogic.Or(GroupIds(UniqueNonEmptyList.of(GroupId("group3"))))
                )
              }
              inside(sortedUserDefinitions.tail.head) { case UserDef(_, patterns, WithoutGroupsMapping(rule1, localGroups)) =>
                patterns should be(UserIdPatterns(UniqueNonEmptyList.of(User.UserIdPattern(userId("morgan")))))
                localGroups should be(UniqueNonEmptyList.of(group("group2", "Group 2"), group("group3", "Group 3")))
                rule1 shouldBe an[AuthKeySha1Rule]
                rule1.asInstanceOf[AuthKeySha1Rule].settings should be {
                  BasicAuthenticationRule.Settings(HashedUserAndPassword("d27aaf7fa3c1603948bb29b7339f2559dc02019a"))
                }
              }
            }
          )
        }
        "single authorization with authentication rule is used for that" in {
          assertDecodingSuccess(
            yaml =
              s"""
                 |readonlyrest:
                 |
                 |  access_control_rules:
                 |
                 |  - name: test_block1
                 |    ${ruleName.name.value}: [group1, group2]
                 |
                 |  users:
                 |  - username: cartman
                 |    groups: ["group1"]
                 |    ldap_auth:
                 |      name: "ldap1"
                 |      groups: ["ldap_group1"]
                 |
                 |  - username: morgan
                 |    groups: ["group2", "group3"]
                 |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
                 |
                 |  ldaps:
                 |  - name: ldap1
                 |    host: ${SingletonLdapContainers.ldap1.ldapHost}
                 |    port: ${SingletonLdapContainers.ldap1.ldapPort}
                 |    ssl_enabled: false
                 |    users:
                 |      search_user_base_DN: "ou=People,dc=example,dc=com"
                 |    groups:
                 |      search_groups_base_DN: "ou=People,dc=example,dc=com"
                 |
                 |""".stripMargin,
            assertion = rule => {
              val groups = ResolvableGroupIds(UniqueNonEmptyList.of(
                AlreadyResolved(GroupId("group1").nel),
                AlreadyResolved(GroupId("group2").nel)
              ))
              rule.settings.permittedGroupsLogic should be(createPermittedGroupsLogicResolver(groups))
              rule.settings.usersDefinitions.length should be(2)
              val sortedUserDefinitions = rule.settings.usersDefinitions
              inside(sortedUserDefinitions.head) { case UserDef(_, patterns, WithGroupsMapping(Auth.SingleRule(rule1), groupMappings)) =>
                patterns should be(UserIdPatterns(UniqueNonEmptyList.of(User.UserIdPattern(userId("cartman")))))
                groupMappings should be(GroupMappings.Simple(UniqueNonEmptyList.of(group("group1"))))
                rule1 shouldBe an[LdapAuthRule]
              }
              inside(sortedUserDefinitions.tail.head) { case UserDef(_, patterns, WithoutGroupsMapping(rule1, localGroups)) =>
                patterns should be(UserIdPatterns(UniqueNonEmptyList.of(User.UserIdPattern(userId("morgan")))))
                localGroups should be(UniqueNonEmptyList.of(group("group2"), group("group3")))
                rule1 shouldBe an[AuthKeySha1Rule]
                rule1.asInstanceOf[AuthKeySha1Rule].settings should be {
                  BasicAuthenticationRule.Settings(HashedUserAndPassword("d27aaf7fa3c1603948bb29b7339f2559dc02019a"))
                }
              }
            }
          )
        }
        "advanced groups mapping with groups in a simple format is used" in {
          assertDecodingSuccess(
            yaml =
              s"""
                 |readonlyrest:
                 |
                 |  access_control_rules:
                 |
                 |  - name: test_block1
                 |    ${ruleName.name.value}: [group1, group3]
                 |
                 |  users:
                 |  - username: cartman
                 |    groups:
                 |     - group1: ["ldap_group3"]
                 |     - group2: ["ldap_group4"]
                 |    auth_key: "cartman:pass"
                 |    groups_provider_authorization:
                 |      user_groups_provider: GroupsService1
                 |      groups: ["ldap_group3", "ldap_group4"]
                 |
                 |  ldaps:
                 |  - name: ldap1
                 |    host: ${SingletonLdapContainers.ldap1.ldapHost}
                 |    port: ${SingletonLdapContainers.ldap1.ldapPort}
                 |    ssl_enabled: false
                 |    users:
                 |      search_user_base_DN: "ou=People,dc=example,dc=com"
                 |    groups:
                 |      search_groups_base_DN: "ou=People,dc=example,dc=com"
                 |
                 |  user_groups_providers:
                 |  - name: GroupsService1
                 |    groups_endpoint: "http://localhost:8080/groups"
                 |    auth_token_name: "user"
                 |    auth_token_passed_as: QUERY_PARAM
                 |    response_group_ids_json_path: "$$..groups[?(@.id)].id"
                 |
                 |""".stripMargin,
            assertion = rule => {
              val groups = ResolvableGroupIds(UniqueNonEmptyList.of(
                AlreadyResolved(GroupId("group1").nel),
                AlreadyResolved(GroupId("group3").nel)
              ))
              rule.settings.permittedGroupsLogic should be(createPermittedGroupsLogicResolver(groups))
              rule.settings.usersDefinitions.length should be(1)
              val sortedUserDefinitions = rule.settings.usersDefinitions
              inside(sortedUserDefinitions.head) { case UserDef(_, patterns, WithGroupsMapping(Auth.SeparateRules(rule1, rule2), groupMappings)) =>
                patterns should be(UserIdPatterns(UniqueNonEmptyList.of(User.UserIdPattern(userId("cartman")))))
                groupMappings should be(GroupMappings.Advanced(UniqueNonEmptyList.of(
                  Mapping(group("group1"), UniqueNonEmptyList.of(GroupId("ldap_group3"))),
                  Mapping(group("group2"), UniqueNonEmptyList.of(GroupId("ldap_group4")))
                )))

                rule1 shouldBe an[AuthKeyRule]
                rule1.asInstanceOf[AuthKeyRule].settings should be {
                  BasicAuthenticationRule.Settings(Credentials(userId("cartman"), PlainTextSecret("pass")))
                }
                rule2 shouldBe an[ExternalAuthorizationRule]
                rule2.asInstanceOf[ExternalAuthorizationRule].settings.permittedGroupsLogic should be(
                  GroupsLogic.Or(GroupIds(UniqueNonEmptyList.of(GroupId("ldap_group3"), GroupId("ldap_group4"))))
                )
              }
            }
          )
        }
        "advanced groups mapping with structured groups is used" in {
          assertDecodingSuccess(
            yaml =
              s"""
                 |readonlyrest:
                 |
                 |  access_control_rules:
                 |
                 |  - name: test_block1
                 |    ${ruleName.name.value}: [group1, group3]
                 |
                 |  users:
                 |  - username: cartman
                 |    groups:
                 |     - local_group:
                 |         id: group1
                 |         name: Group 1
                 |       external_group_ids: ["ldap_group3"]
                 |     - local_group:
                 |         id: group2
                 |         name: Group 2
                 |       external_group_ids: ["ldap_group4"]
                 |    auth_key: "cartman:pass"
                 |    groups_provider_authorization:
                 |      user_groups_provider: GroupsService1
                 |      groups: ["ldap_group3", "ldap_group4"]
                 |
                 |  ldaps:
                 |  - name: ldap1
                 |    host: ${SingletonLdapContainers.ldap1.ldapHost}
                 |    port: ${SingletonLdapContainers.ldap1.ldapPort}
                 |    ssl_enabled: false
                 |    users:
                 |      search_user_base_DN: "ou=People,dc=example,dc=com"
                 |    groups:
                 |      search_groups_base_DN: "ou=People,dc=example,dc=com"
                 |
                 |  user_groups_providers:
                 |  - name: GroupsService1
                 |    groups_endpoint: "http://localhost:8080/groups"
                 |    auth_token_name: "user"
                 |    auth_token_passed_as: QUERY_PARAM
                 |    response_group_ids_json_path: "$$..groups[?(@.id)].id"
                 |
                 |""".stripMargin,
            assertion = rule => {
              val groups = ResolvableGroupIds(UniqueNonEmptyList.of(
                AlreadyResolved(GroupId("group1").nel),
                AlreadyResolved(GroupId("group3").nel)
              ))
              rule.settings.permittedGroupsLogic should be(createPermittedGroupsLogicResolver(groups))
              rule.settings.usersDefinitions.length should be(1)
              val sortedUserDefinitions = rule.settings.usersDefinitions
              inside(sortedUserDefinitions.head) { case UserDef(_, patterns, WithGroupsMapping(Auth.SeparateRules(rule1, rule2), groupMappings)) =>
                patterns should be(UserIdPatterns(UniqueNonEmptyList.of(User.UserIdPattern(userId("cartman")))))
                groupMappings should be(GroupMappings.Advanced(UniqueNonEmptyList.of(
                  Mapping(group("group1", "Group 1"), UniqueNonEmptyList.of(GroupId("ldap_group3"))),
                  Mapping(group("group2", "Group 2"), UniqueNonEmptyList.of(GroupId("ldap_group4")))
                )))

                rule1 shouldBe an[AuthKeyRule]
                rule1.asInstanceOf[AuthKeyRule].settings should be {
                  BasicAuthenticationRule.Settings(Credentials(userId("cartman"), PlainTextSecret("pass")))
                }
                rule2 shouldBe an[ExternalAuthorizationRule]
                rule2.asInstanceOf[ExternalAuthorizationRule].settings.permittedGroupsLogic should be(
                  GroupsLogic.Or(GroupIds(UniqueNonEmptyList.of(GroupId("ldap_group3"), GroupId("ldap_group4"))))
                )
              }
            }
          )
        }
      }
      "users inside users sections must have the same username patterns" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    ${ruleName.name.value}: ["group1"]
               |
               |  users:
               |  - username: "*"
               |    groups: ["group1", "group3"]
               |    auth_key: "cartman:pass"
               |
               |  - username: "*"
               |    groups: ["group2", "group3"]
               |    auth_key: "morgan:pass"
               |
               |""".stripMargin,
          assertion = rule => {
            val groups = ResolvableGroupIds(UniqueNonEmptyList.of(
              AlreadyResolved(GroupId("group1").nel)
            ))
            rule.settings.permittedGroupsLogic should be(createPermittedGroupsLogicResolver(groups))
            rule.settings.usersDefinitions.length should be(2)
            val sortedUserDefinitions = rule.settings.usersDefinitions
            inside(sortedUserDefinitions.head) { case UserDef(_, patterns, WithoutGroupsMapping(r, localGroups)) =>
              patterns should be(UserIdPatterns(UniqueNonEmptyList.of(User.UserIdPattern(userId("*")))))
              localGroups should be(UniqueNonEmptyList.of(group("group1"), group("group3")))
              r shouldBe an[AuthKeyRule]
              r.asInstanceOf[AuthKeyRule].settings should be {
                BasicAuthenticationRule.Settings(Credentials(userId("cartman"), PlainTextSecret("pass")))
              }
            }
            inside(sortedUserDefinitions.tail.head) { case UserDef(_, patterns, WithoutGroupsMapping(r, localGroups)) =>
              patterns should be(UserIdPatterns(UniqueNonEmptyList.of(User.UserIdPattern(userId("*")))))
              localGroups should be(UniqueNonEmptyList.of(group("group2"), group("group3")))
              r shouldBe an[AuthKeyRule]
              r.asInstanceOf[AuthKeyRule].settings should be {
                BasicAuthenticationRule.Settings(Credentials(userId("morgan"), PlainTextSecret("pass")))
              }
            }
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "groups section is defined, but without any group" in {
        assertDecodingFailure(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    ${ruleName.name.value}:
               |
               |  users:
               |  - username: cartman
               |    groups: ["group1", "group3"]
               |    auth_key: "cartman:pass"
               |
               |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue.fromString(
              s"""${ruleName.name.value}: null
                 |""".stripMargin
            )))
          }
        )
      }
      "no users definitions section is defined" in {
        assertDecodingFailure(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    ${ruleName.name.value}: group1
               |
               |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message(s"No user definitions was defined. Rule `${ruleName.name.value}` requires them.")))
          }
        )
      }
      "username in user definitions is empty" in {
        assertDecodingFailure(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    ${ruleName.name.value}: group1
               |
               |  users:
               |  - username:
               |    groups: ["group1", "group3"]
               |    auth_key: "cartman:pass"
               |
               |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Error for field 'username': Non empty list of user ID patterns are required")))
          }
        )
      }
      "username in users definition is a empty list" in {
        assertDecodingFailure(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    ${ruleName.name.value}: group1
               |
               |  users:
               |  - username: []
               |    groups: ["group1", "group3"]
               |    auth_key: "cartman:pass"
               |
               |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Error for field 'username': Non empty list of user ID patterns are required")))
          }
        )
      }
      "username filed is absent in user definition" in {
        assertDecodingFailure(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    ${ruleName.name.value}: group1
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
      "username is used in two definitions" in {
        val basicAuthenticationRules = List("auth_key", "auth_key_sha1", "auth_key_sha256", "auth_key_sha512", "auth_key_pbkdf2", "auth_key_unix")

        val testCases = for {
          rule1 <- basicAuthenticationRules
          rule2 <- basicAuthenticationRules
        } yield (rule1, rule2)

        testCases.foreach { case (authRule1, authRule2) =>
          assertDecodingFailure(
            yaml =
              s"""
                 |readonlyrest:
                 |
                 |  access_control_rules:
                 |
                 |  - name: test_block1
                 |    ${ruleName.name.value}: ["group*"]
                 |
                 |  users:
                 |  - username: cartman
                 |    groups: ["group1", "group3"]
                 |    $authRule1: "cartman:pass"
                 |
                 |  - username: cartman
                 |    groups: ["group2", "group4"]
                 |    $authRule2: "cartman:pass"
                 |
                 |""".stripMargin,
            assertion = errors => {
              errors shouldEqual NonEmptyList.of(DefinitionsLevelCreationError(Message(
                "The `users` definition is malformed: Username 'cartman' is duplicated - full usernames can be used only in one definition."
              )))
            }
          )
        }
      }
      "groups set in user definitions is not defined" in {
        assertDecodingFailure(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    ${ruleName.name.value}:
               |
               |  users:
               |  - username: cartman
               |    groups:
               |    auth_key: "cartman:pass"
               |
               |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Non empty list of groups is required")))
          }
        )
      }
      "groups set in user definitions is empty" in {
        assertDecodingFailure(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    ${ruleName.name.value}:
               |
               |  users:
               |  - username: cartman
               |    groups: []
               |    auth_key: "cartman:pass"
               |
               |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Non empty list of groups is required")))
          }
        )
      }
      "mapped group IDs array in user definition is empty" in {
        assertDecodingFailure(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    ${ruleName.name.value}: [group1, group3]
               |
               |  users:
               |  - username: cartman
               |    groups:
               |     - local_group:
               |         id: group1
               |         name: Group 1
               |       external_group_ids: ["ldap_group3"]
               |     - local_group:
               |         id: group2
               |         name: Group 2
               |       external_group_ids: []
               |    auth_key: "cartman:pass"
               |    groups_provider_authorization:
               |      user_groups_provider: GroupsService1
               |      groups: ["ldap_group3", "ldap_group4"]
               |
               |  ldaps:
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    users:
               |      search_user_base_DN: "ou=People,dc=example,dc=com"
               |    groups:
               |      search_groups_base_DN: "ou=People,dc=example,dc=com"
               |
               |  user_groups_providers:
               |  - name: GroupsService1
               |    groups_endpoint: "http://localhost:8080/groups"
               |    auth_token_name: "user"
               |    auth_token_passed_as: QUERY_PARAM
               |    response_group_ids_json_path: "$$..groups[?(@.id)].id"
               |
               |""".stripMargin
          ,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message(
              "Error for field 'external_group_ids': Non empty list of group IDs or/and patterns is required"
            )))
          }
        )
      }
      "mapped group IDs array in user definition is empty (old syntax)" in {
        assertDecodingFailure(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    ${ruleName.name.value}: [group1, group3]
               |
               |  users:
               |  - username: cartman
               |    groups:
               |     - "group1": []
               |    auth_key: "cartman:pass"
               |    groups_provider_authorization:
               |      user_groups_provider: GroupsService1
               |      groups: ["ldap_group3", "ldap_group4"]
               |
               |  ldaps:
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    users:
               |      search_user_base_DN: "ou=People,dc=example,dc=com"
               |    groups:
               |      search_groups_base_DN: "ou=People,dc=example,dc=com"
               |
               |  user_groups_providers:
               |  - name: GroupsService1
               |    groups_endpoint: "http://localhost:8080/groups"
               |    auth_token_name: "user"
               |    auth_token_passed_as: QUERY_PARAM
               |    response_group_ids_json_path: "$$..groups[?(@.id)].id"
               |
               |""".stripMargin
          ,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message(
              "Error for field 'group1': Non empty list of group IDs or/and patterns is required"
            )))
          }
        )
      }
      "group mappings in user definition contain an empty mapped group ID" in {
        assertDecodingFailure(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    ${ruleName.name.value}: [group1, group3]
               |
               |  users:
               |  - username: cartman
               |    groups:
               |     - local_group:
               |         id: group1
               |         name: Group 1
               |       external_group_ids: [""]
               |    auth_key: "cartman:pass"
               |    groups_provider_authorization:
               |      user_groups_provider: GroupsService1
               |      groups: ["ldap_group3", "ldap_group4"]
               |
               |  ldaps:
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    users:
               |      search_user_base_DN: "ou=People,dc=example,dc=com"
               |    groups:
               |      search_groups_base_DN: "ou=People,dc=example,dc=com"
               |
               |  user_groups_providers:
               |  - name: GroupsService1
               |    groups_endpoint: "http://localhost:8080/groups"
               |    auth_token_name: "user"
               |    auth_token_passed_as: QUERY_PARAM
               |    response_group_ids_json_path: "$$..groups[?(@.id)].id"
               |
               |""".stripMargin
          ,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message(
              "Error for field 'external_group_ids': Non empty list of group IDs or/and patterns is required"
            )))
          }
        )
      }
      "group ID in user definition is empty" in {
        assertDecodingFailure(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    ${ruleName.name.value}: [group1, group3]
               |
               |  users:
               |  - username: cartman
               |    groups:
               |     - local_group:
               |         id: ""
               |         name: Group 1
               |       external_group_ids: ["ldap_group3"]
               |    auth_key: "cartman:pass"
               |    groups_provider_authorization:
               |      user_groups_provider: GroupsService1
               |      groups: ["ldap_group3", "ldap_group4"]
               |
               |  ldaps:
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    users:
               |      search_user_base_DN: "ou=People,dc=example,dc=com"
               |    groups:
               |      search_groups_base_DN: "ou=People,dc=example,dc=com"
               |
               |  user_groups_providers:
               |  - name: GroupsService1
               |    groups_endpoint: "http://localhost:8080/groups"
               |    auth_token_name: "user"
               |    auth_token_passed_as: QUERY_PARAM
               |    response_group_ids_json_path: "$$..groups[?(@.id)].id"
               |
               |""".stripMargin
          ,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message(
              "Error for field 'id': Group ID cannot be an empty string"
            )))
          }
        )
      }
      "group name in user definition is empty" in {
        assertDecodingFailure(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    ${ruleName.name.value}: [group1, group3]
               |
               |  users:
               |  - username: cartman
               |    groups:
               |     - local_group:
               |         id: group1
               |         name: ""
               |       external_group_ids: ["ldap_group3"]
               |    auth_key: "cartman:pass"
               |    groups_provider_authorization:
               |      user_groups_provider: GroupsService1
               |      groups: ["ldap_group3", "ldap_group4"]
               |
               |  ldaps:
               |  - name: ldap1
               |    host: ${SingletonLdapContainers.ldap1.ldapHost}
               |    port: ${SingletonLdapContainers.ldap1.ldapPort}
               |    ssl_enabled: false
               |    users:
               |      search_user_base_DN: "ou=People,dc=example,dc=com"
               |    groups:
               |      search_groups_base_DN: "ou=People,dc=example,dc=com"
               |
               |  user_groups_providers:
               |  - name: GroupsService1
               |    groups_endpoint: "http://localhost:8080/groups"
               |    auth_token_name: "user"
               |    auth_token_passed_as: QUERY_PARAM
               |    response_group_ids_json_path: "$$..groups[?(@.id)].id"
               |
               |""".stripMargin
          ,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message(
              "Error for field 'name': Group name cannot be an empty string"
            )))
          }
        )
      }
    }
    "only one authentication rule can be defined for user in users section" in {
      assertDecodingFailure(
        yaml =
          s"""
             |readonlyrest:
             |
             |  access_control_rules:
             |
             |  - name: test_block1
             |    ${ruleName.name.value}: ["group1"]
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
          errors.head should be(DefinitionsLevelCreationError(Message(
            """Users definition section external groups mapping feature allows for single rule with authentication
              | and authorization or two rules which handle authentication and authorization separately. 'auth_key'
              | and 'auth_key_sha1' should be authentication and authorization rules""".stripMargin
          )))
        }
      )
    }
    "only one authorization rule can be defined for user in users section" in {
      assertDecodingFailure(
        yaml =
          s"""
             |readonlyrest:
             |
             |  access_control_rules:
             |
             |  - name: test_block1
             |    ${ruleName.name.value}: ["group1"]
             |
             |  users:
             |  - username: cartman
             |    groups: ["group1", "group3"]
             |    auth_key: "cartman:pass"
             |    ldap_authorization:
             |      name: "ldap1"
             |      groups: ["ldap_group1"]
             |    groups_provider_authorization:
             |      user_groups_provider: GroupsService1
             |      groups: ["group3"]
             |
             |  ldaps:
             |  - name: ldap1
             |    host: ${SingletonLdapContainers.ldap1.ldapHost}
             |    port: ${SingletonLdapContainers.ldap1.ldapPort}
             |    ssl_enabled: false
             |    users:
             |      search_user_base_DN: "ou=People,dc=example,dc=com"
             |    groups:
             |      search_groups_base_DN: "ou=People,dc=example,dc=com"
             |
             |  user_groups_providers:
             |  - name: GroupsService1
             |    groups_endpoint: "http://localhost:8080/groups"
             |    auth_token_name: "user"
             |    auth_token_passed_as: QUERY_PARAM
             |    response_group_ids_json_path: "$$..groups[?(@.id)].id"
             |
             |""".stripMargin,
        assertion = errors => {
          errors should have size 1
          errors.head should be(DefinitionsLevelCreationError(Message(
            "Too many rules defined for [cartman] in users definition section: auth_key, ldap_authorization, groups_provider_authorization"
          )))
        }
      )
    }
    "single authorization and authentication rule cannot be used together with different authentication rule for user in users section" in {
      assertDecodingFailure(
        yaml =
          s"""
             |readonlyrest:
             |
             |  access_control_rules:
             |
             |  - name: test_block1
             |    ${ruleName.name.value}:
             |
             |  users:
             |  - username: cartman
             |    groups: ["group1", "group3"]
             |    auth_key: "cartman:pass"
             |    ldap_auth:
             |      name: "ldap1"
             |      groups: ["ldap_group1"]
             |
             |  ldaps:
             |  - name: ldap1
             |    host: ${SingletonLdapContainers.ldap1.ldapHost}
             |    port: ${SingletonLdapContainers.ldap1.ldapPort}
             |    ssl_enabled: false
             |    users:
             |      search_user_base_DN: "ou=People,dc=example,dc=com"
             |    groups:
             |      search_groups_base_DN: "ou=People,dc=example,dc=com"
             |
             |""".stripMargin,
        assertion = errors => {
          errors should have size 1
          errors.head should be(DefinitionsLevelCreationError(Message(
            """Users definition section external groups mapping feature allows for single rule with authentication
              | and authorization or two rules which handle authentication and authorization separately. 'ldap_auth'
              | is an authentication with authorization rule and 'auth_key' is and authentication only rule.
              | Cannot use them both in this context.""".stripMargin
          )))
        }
      )
    }
    "single authorization and authentication rule cannot be used together with different authorization rule for user in users section" in {
      assertDecodingFailure(
        yaml =
          s"""
             |readonlyrest:
             |
             |  access_control_rules:
             |
             |  - name: test_block1
             |    ${ruleName.name.value}:
             |
             |  users:
             |  - username: cartman
             |    groups: ["group1", "group3"]
             |    ldap_auth:
             |      name: "ldap1"
             |      groups: ["ldap_group1"]
             |    groups_provider_authorization:
             |      user_groups_provider: GroupsService1
             |      groups: ["group3"]
             |
             |  ldaps:
             |  - name: ldap1
             |    host: ${SingletonLdapContainers.ldap1.ldapHost}
             |    port: ${SingletonLdapContainers.ldap1.ldapPort}
             |    ssl_enabled: false
             |    users:
             |      search_user_base_DN: "ou=People,dc=example,dc=com"
             |    groups:
             |      search_groups_base_DN: "ou=People,dc=example,dc=com"
             |
             |  user_groups_providers:
             |  - name: GroupsService1
             |    groups_endpoint: "http://localhost:8080/groups"
             |    auth_token_name: "user"
             |    auth_token_passed_as: QUERY_PARAM
             |    response_group_ids_json_path: "$$..groups[?(@.id)].id"
             |
             |""".stripMargin,
        assertion = errors => {
          errors should have size 1
          errors.head should be(DefinitionsLevelCreationError(Message(
            """Users definition section external groups mapping feature allows for single rule with authentication
              | and authorization or two rules which handle authentication and authorization separately. 'ldap_auth'
              | is an authentication with authorization rule and 'groups_provider_authorization' is and authorization only rule.
              | Cannot use them both in this context.""".stripMargin
          )))
        }
      )
    }
    "auth key rule inside user definition is unparsable" in {
      assertDecodingFailure(
        yaml =
          s"""
             |readonlyrest:
             |
             |  access_control_rules:
             |
             |  - name: test_block1
             |    ${ruleName.name.value}: group1
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
          errors.head should be(DefinitionsLevelCreationError(MalformedValue.fromString(
            """auth_key:
              |  key: "cartman:pass"
              |""".stripMargin)))
        }
      )
    }
    "user definition doesn't allow to use unknown rules" in {
      assertDecodingFailure(
        yaml =
          s"""
             |readonlyrest:
             |
             |  access_control_rules:
             |
             |  - name: test_block1
             |    ${ruleName.name.value}:
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
          errors.head should be(DefinitionsLevelCreationError(Message("Unknown rule 'unknown_field' in users definitions section")))
        }
      )
    }
    "authentication rule is used, but user cannot be matched by username patterns" in {
      assertDecodingFailure(
        yaml =
          s"""
             |readonlyrest:
             |
             |  access_control_rules:
             |
             |  - name: test_block1
             |    ${ruleName.name.value}: group1
             |
             |  users:
             |  - username: a*
             |    groups: ["group1", "group3"]
             |    auth_key: "cartman:pass"
             |
             |""".stripMargin,
        assertion = errors => {
          errors should have size 1
          errors.head should be(DefinitionsLevelCreationError(Message("Users [cartman] are allowed to be authenticated by rule [auth_key], but it's used in a context of user patterns [a*]. It seems that this is not what you expect.")))
        }
      )
    }
  }
}
