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

import com.dimafeng.testcontainers.ForAllTestContainer
import eu.timepit.refined.auto._
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers._
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef
import tech.beshu.ror.accesscontrol.blocks.rules.AuthKeyHashingRule.HashedCredentials.HashedUserAndPassword
import tech.beshu.ror.accesscontrol.blocks.rules.{AuthKeyRule, AuthKeySha1Rule, BasicAuthenticationRule, GroupsRule}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.{AlreadyResolved, ToBeResolved}
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.{DefinitionsLevelCreationError, RulesLevelCreationError}
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.containers.LdapContainer
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class GroupsRuleSettingsTests
  extends BaseRuleSettingsDecoderTest[GroupsRule]
    with ForAllTestContainer
    with Inside {

  override val container: LdapContainer = new LdapContainer("LDAP1", "test_example.ldif")

  "A GroupsRule" should {
    "be able to be loaded from config" when {
      "a groups mapping is not used" when {
        "only one group is defined" when {
          "one, full username is used" in {
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
                val groups: UniqueNonEmptyList[RuntimeMultiResolvableVariable[Group]] = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("group1").nel))
                rule.settings.groups should be(groups)
                rule.settings.usersDefinitions.length should be(1)
                inside(rule.settings.usersDefinitions.head) { case UserDef(patterns, userGroups, authRule) =>
                  patterns should be(UserIdPatterns(UniqueNonEmptyList.of(User.UserIdPattern("cartman"))))
                  userGroups should be(UniqueNonEmptyList.of(groupFrom("group1"), groupFrom("group3")))
                  authRule shouldBe an[AuthKeyRule]
                  authRule.asInstanceOf[AuthKeyRule].settings should be {
                    BasicAuthenticationRule.Settings(Credentials(User.Id("cartman"), PlainTextSecret("pass")))
                  }
                }
              }
            )
          }
          "one, username with wildcard is used" in {
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
                  |  - username: car*
                  |    groups: ["group1", "group3"]
                  |    auth_key: "cartman:pass"
                  |
                  |""".stripMargin,
              assertion = rule => {
                val groups: UniqueNonEmptyList[RuntimeMultiResolvableVariable[Group]] = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("group1").nel))
                rule.settings.groups should be(groups)
                rule.settings.usersDefinitions.length should be(1)
                inside(rule.settings.usersDefinitions.head) { case UserDef(patterns, userGroups, authRule) =>
                  patterns should be(UserIdPatterns(UniqueNonEmptyList.of(User.UserIdPattern("car*"))))
                  userGroups should be(UniqueNonEmptyList.of(groupFrom("group1"), groupFrom("group3")))
                  authRule shouldBe an[AuthKeyRule]
                  authRule.asInstanceOf[AuthKeyRule].settings should be {
                    BasicAuthenticationRule.Settings(Credentials(User.Id("cartman"), PlainTextSecret("pass")))
                  }
                }
              }
            )
          }
          "two usernames are used (the first one - full, the second - with wildcard)" in {
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
                  |  - username: [cartman, "ca*"]
                  |    groups: ["group1", "group3"]
                  |    auth_key: "cartman:pass"
                  |
                  |""".stripMargin,
              assertion = rule => {
                val groups: UniqueNonEmptyList[RuntimeMultiResolvableVariable[Group]] = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("group1").nel))
                rule.settings.groups should be(groups)
                rule.settings.usersDefinitions.length should be(1)
                inside(rule.settings.usersDefinitions.head) { case UserDef(patterns, userGroups, authRule) =>
                  patterns should be(UserIdPatterns(UniqueNonEmptyList.of(User.UserIdPattern("cartman"), User.UserIdPattern("ca*"))))
                  userGroups should be(UniqueNonEmptyList.of(groupFrom("group1"), groupFrom("group3")))
                  authRule shouldBe an[AuthKeyRule]
                  authRule.asInstanceOf[AuthKeyRule].settings should be {
                    BasicAuthenticationRule.Settings(Credentials(User.Id("cartman"), PlainTextSecret("pass")))
                  }
                }
              }
            )
          }
        }
        "several groups are defined" when {
          "no variables are used in group names" in {
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
                val groups: UniqueNonEmptyList[RuntimeMultiResolvableVariable[Group]] =
                  UniqueNonEmptyList.of(AlreadyResolved(groupFrom("group1").nel), AlreadyResolved(groupFrom("group2").nel))
                rule.settings.groups should be(groups)
                rule.settings.usersDefinitions.length should be(2)
                val sortedUserDefinitions = rule.settings.usersDefinitions
                inside(sortedUserDefinitions.head) { case UserDef(patterns, userGroups, authRule) =>
                  patterns should be(UserIdPatterns(UniqueNonEmptyList.of(User.UserIdPattern("cartman"))))
                  userGroups should be(UniqueNonEmptyList.of(groupFrom("group1"), groupFrom("group3")))
                  authRule shouldBe an[AuthKeyRule]
                  authRule.asInstanceOf[AuthKeyRule].settings should be {
                    BasicAuthenticationRule.Settings(Credentials(User.Id("cartman"), PlainTextSecret("pass")))
                  }
                }
                inside(sortedUserDefinitions.tail.head) { case UserDef(patterns, userGroups, authRule) =>
                  patterns should be(UserIdPatterns(UniqueNonEmptyList.of(User.UserIdPattern("morgan"))))
                  userGroups should be(UniqueNonEmptyList.of(groupFrom("group2"), groupFrom("group3")))
                  authRule shouldBe an[AuthKeySha1Rule]
                  authRule.asInstanceOf[AuthKeySha1Rule].settings should be {
                    BasicAuthenticationRule.Settings(HashedUserAndPassword("d27aaf7fa3c1603948bb29b7339f2559dc02019a"))
                  }
                }
              }
            )
          }
          "a variable in used in one group name" in {
            assertDecodingSuccess(
              yaml =
                """
                  |readonlyrest:
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block1
                  |    auth_key: user:pass
                  |    groups: [group1, "group_@{user}"]
                  |
                  |  users:
                  |  - username: cartman
                  |    groups: ["group1", "group3"]
                  |    auth_key: "cartman:pass"
                  |
                  |""".stripMargin,
              assertion = rule => {
                rule.settings.groups.size shouldBe 2
                rule.settings.groups.head should be(AlreadyResolved(groupFrom("group1").nel))
                rule.settings.groups.tail.head shouldBe a[ToBeResolved[_]]

                rule.settings.usersDefinitions.length should be(1)
                inside(rule.settings.usersDefinitions.head) { case UserDef(patterns, userGroups, authRule) =>
                  patterns should be(UserIdPatterns(UniqueNonEmptyList.of(User.UserIdPattern("cartman"))))
                  userGroups should be(UniqueNonEmptyList.of(groupFrom("group1"), groupFrom("group3")))
                  authRule shouldBe an[AuthKeyRule]
                  authRule.asInstanceOf[AuthKeyRule].settings should be {
                    BasicAuthenticationRule.Settings(Credentials(User.Id("cartman"), PlainTextSecret("pass")))
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
                 |    groups: [group1, group2]
                 |
                 |  users:
                 |  - username: cartman
                 |    groups: ["group1"]
                 |    auth_key: "cartman:pass"
                 |    groups_provider_authorization:
                 |      user_groups_provider: GroupsService1
                 |      groups: ["group3"]
                 |
                 |  - username: morgan
                 |    groups: ["group2", "group3"]
                 |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
                 |
                 |  ldaps:
                 |  - name: ldap1
                 |    host: ${container.ldapHost}
                 |    port: ${container.ldapPort}
                 |    ssl_enabled: false
                 |    search_user_base_DN: "ou=People,dc=example,dc=com"
                 |    search_groups_base_DN: "ou=People,dc=example,dc=com"
                 |
                 |  user_groups_providers:
                 |  - name: GroupsService1
                 |    groups_endpoint: "http://localhost:8080/groups"
                 |    auth_token_name: "user"
                 |    auth_token_passed_as: QUERY_PARAM
                 |    response_groups_json_path: "$$..groups[?(@.name)].name"
                 |
                 |""".stripMargin,
            assertion = rule => {
              val groups: UniqueNonEmptyList[RuntimeMultiResolvableVariable[Group]] =
                UniqueNonEmptyList.of(AlreadyResolved(groupFrom("group1").nel), AlreadyResolved(groupFrom("group2").nel))
              rule.settings.groups should be(groups)
              rule.settings.usersDefinitions.length should be(2)
              val sortedUserDefinitions = rule.settings.usersDefinitions
              inside(sortedUserDefinitions.head) { case UserDef(patterns, userGroups, authRule) =>
                patterns should be(UserIdPatterns(UniqueNonEmptyList.of(User.UserIdPattern("cartman"))))
                userGroups should be(UniqueNonEmptyList.of(groupFrom("group1"), groupFrom("group3")))
                authRule shouldBe an[AuthKeyRule]
                authRule.asInstanceOf[AuthKeyRule].settings should be {
                  BasicAuthenticationRule.Settings(Credentials(User.Id("cartman"), PlainTextSecret("pass")))
                }
              }
              inside(sortedUserDefinitions.tail.head) { case UserDef(patterns, userGroups, authRule) =>
                patterns should be(UserIdPatterns(UniqueNonEmptyList.of(User.UserIdPattern("morgan"))))
                userGroups should be(UniqueNonEmptyList.of(groupFrom("group2"), groupFrom("group3")))
                authRule shouldBe an[AuthKeySha1Rule]
                authRule.asInstanceOf[AuthKeySha1Rule].settings should be {
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
                 |    groups: [group1, group2]
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
                 |    host: ${container.ldapHost}
                 |    port: ${container.ldapPort}
                 |    ssl_enabled: false
                 |    search_user_base_DN: "ou=People,dc=example,dc=com"
                 |    search_groups_base_DN: "ou=People,dc=example,dc=com"
                 |
                 |""".stripMargin,
            assertion = rule => {
              val groups: UniqueNonEmptyList[RuntimeMultiResolvableVariable[Group]] =
                UniqueNonEmptyList.of(AlreadyResolved(groupFrom("group1").nel), AlreadyResolved(groupFrom("group2").nel))
              rule.settings.groups should be(groups)
              rule.settings.usersDefinitions.length should be(2)
              val sortedUserDefinitions = rule.settings.usersDefinitions
              inside(sortedUserDefinitions.head) { case UserDef(patterns, userGroups, authRule) =>
                patterns should be(UserIdPatterns(UniqueNonEmptyList.of(User.UserIdPattern("cartman"))))
                userGroups should be(UniqueNonEmptyList.of(groupFrom("group1"), groupFrom("group3")))
                authRule shouldBe an[AuthKeyRule]
                authRule.asInstanceOf[AuthKeyRule].settings should be {
                  BasicAuthenticationRule.Settings(Credentials(User.Id("cartman"), PlainTextSecret("pass")))
                }
              }
              inside(sortedUserDefinitions.tail.head) { case UserDef(patterns, userGroups, authRule) =>
                patterns should be(UserIdPatterns(UniqueNonEmptyList.of(User.UserIdPattern("morgan"))))
                userGroups should be(UniqueNonEmptyList.of(groupFrom("group2"), groupFrom("group3")))
                authRule shouldBe an[AuthKeySha1Rule]
                authRule.asInstanceOf[AuthKeySha1Rule].settings should be {
                  BasicAuthenticationRule.Settings(HashedUserAndPassword("d27aaf7fa3c1603948bb29b7339f2559dc02019a"))
                }
              }
            }
          )
        }
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
            errors.head should be(DefinitionsLevelCreationError(Message("Non empty list of user ID patterns are required")))
          }
        )
      }
      "username in users definition is a empty list" in {
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
              |  - username: []
              |    groups: ["group1", "group3"]
              |    auth_key: "cartman:pass"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Non empty list of user ID patterns are required")))
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
              |    groups: ["group1"]
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
            errors.head should be(DefinitionsLevelCreationError(Message("Only one authentication should be defined for [cartman]. Found auth_key, auth_key_sha1")))
          }
        )
      }
      "only one authorization rule can be defined for user in users section" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups: ["group1"]
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
              |    host: ${container.ldapHost}
              |    port: ${container.ldapPort}
              |    ssl_enabled: false
              |    search_user_base_DN: "ou=People,dc=example,dc=com"
              |    search_groups_base_DN: "ou=People,dc=example,dc=com"
              |
              |  user_groups_providers:
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user"
              |    auth_token_passed_as: QUERY_PARAM
              |    response_groups_json_path: "$$..groups[?(@.name)].name"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Only one authentication should be defined for [cartman]. Found auth_key, auth_key_sha1")))
          }
        )
      }
      "groups rule cannot be used as single authorization and authentication rule in users section" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups: ["group1"]
              |
              |  users:
              |  - username: cartman
              |    groups: ["group1", "group3"]
              |    groups: ["group1"]
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Only one authentication should be defined for [cartman]. Found auth_key, auth_key_sha1")))
          }
        )
      }
      "groups rule cannot be used as authentication rule in users section" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups: ["group1"]
              |
              |  users:
              |  - username: cartman
              |    groups: ["group1", "group3"]
              |    groups: ["group1"]
              |    groups_provider_authorization:
              |      user_groups_provider: GroupsService1
              |      groups: ["group3"]
              |
              |  user_groups_providers:
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user"
              |    auth_token_passed_as: QUERY_PARAM
              |    response_groups_json_path: "$$..groups[?(@.name)].name"
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Only one authentication should be defined for [cartman]. Found auth_key, auth_key_sha1")))
          }
        )
      }
      "groups rule cannot be used as authorization rule in users section" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    groups: ["group1"]
              |
              |  users:
              |  - username: cartman
              |    groups: ["group1", "group3"]
              |    auth_key: "cartman:pass"
              |    groups: ["group1"]
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Only one authentication should be defined for [cartman]. Found auth_key, auth_key_sha1")))
          }
        )
      }
      "single authorization and authentication rule cannot be used together with different authentication rule for user in users section" in {
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
              |    ldap_auth:
              |      name: "ldap1"
              |      groups: ["ldap_group1"]
              |
              |  ldaps:
              |  - name: ldap1
              |    host: ${container.ldapHost}
              |    port: ${container.ldapPort}
              |    ssl_enabled: false
              |    search_user_base_DN: "ou=People,dc=example,dc=com"
              |    search_groups_base_DN: "ou=People,dc=example,dc=com"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Only one authentication should be defined for [cartman]. Found auth_key, auth_key_sha1")))
          }
        )
      }
      "single authorization and authentication rule cannot be used together with different authorization rule for user in users section" in {
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
              |    ldap_auth:
              |      name: "ldap1"
              |      groups: ["ldap_group1"]
              |    groups_provider_authorization:
              |      user_groups_provider: GroupsService1
              |      groups: ["group3"]
              |
              |  ldaps:
              |  - name: ldap1
              |    host: ${container.ldapHost}
              |    port: ${container.ldapPort}
              |    ssl_enabled: false
              |    search_user_base_DN: "ou=People,dc=example,dc=com"
              |    search_groups_base_DN: "ou=People,dc=example,dc=com"
              |
              |  user_groups_providers:
              |  - name: GroupsService1
              |    groups_endpoint: "http://localhost:8080/groups"
              |    auth_token_name: "user"
              |    auth_token_passed_as: QUERY_PARAM
              |    response_groups_json_path: "$$..groups[?(@.name)].name"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Only one authentication should be defined for [cartman]. Found auth_key, auth_key_sha1")))
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
            errors.head should be(DefinitionsLevelCreationError(Message("Cannot parse 'auth_key' rule declared for [cartman]")))
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
            errors.head should be(DefinitionsLevelCreationError(Message("Only one authentication should be defined for [cartman]. Found unknown_field, auth_key")))
          }
        )
      }
      "auth rule is used, but user cannot be matched by username patterns" in {
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
}
