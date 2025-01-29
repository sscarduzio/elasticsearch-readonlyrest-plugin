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
package tech.beshu.ror.unit.acl.blocks.rules.auth

import cats.data.NonEmptyList
import org.scalatest.matchers.should.Matchers.*
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.GroupMappings.Advanced.Mapping
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.Mode.WithGroupsMapping.Auth.{SeparateRules, SingleRule}
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.Mode.{WithGroupsMapping, WithoutGroupsMapping}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.BaseGroupsRule.Settings as GroupsRulesSettings
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.AlwaysRightConvertible
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

import scala.language.postfixOps

trait BaseGroupsNegativeRuleTests extends BaseGroupsRuleTests {

  "An AbstractNegativeGroupsRule" should {
    "not match because of reasons other than presence of forbidden group" when {
      "groups mapping is not configured" when {
        "no group can be resolved" in {
          assertNotMatchRule(
            settings = GroupsRulesSettings(
              groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(
                createVariable("group_@{user}")(AlwaysRightConvertible.from(GroupIdLike.from)).toOption.get
              )),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                mode = WithoutGroupsMapping(authenticationRule.rejecting, groups("group_user1"))
              ))
            ),
            loggedUser = None,
            preferredGroupId = None
          )
        }
        "resolved groups don't contain preferred group" in {
          assertNotMatchRule(
            settings = GroupsRulesSettings(
              groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupIdLike.from("g1").nel))),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                mode = WithoutGroupsMapping(authenticationRule.rejecting, groups("g1"))
              ))
            ),
            loggedUser = None,
            preferredGroupId = Some(GroupId("g2"))
          )
        }
        "there is no user definition for given logged user" in {
          assertNotMatchRule(
            settings = GroupsRulesSettings(
              groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                mode = WithoutGroupsMapping(authenticationRule.rejecting, groups("g1"))
              ))
            ),
            loggedUser = Some(User.Id("user2")),
            preferredGroupId = None
          )
        }
        "there is no matching auth rule for given user" in {
          assertNotMatchRule(
            settings = GroupsRulesSettings(
              groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                mode = WithoutGroupsMapping(authenticationRule.rejecting, groups("g1"))
              ))
            ),
            loggedUser = Some(User.Id("user1")),
            preferredGroupId = None
          )
        }
        "case sensitivity is configured, but authentication rule authenticates user with name with a capital letter at the beginning" in {
          assertNotMatchRule(
            settings = GroupsRulesSettings(
              groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("u*"),
                mode = WithoutGroupsMapping(authenticationRule.matching(User.Id("User1")), groups("g1"))
              ))
            ),
            loggedUser = None,
            preferredGroupId = None
          )
        }
        "one auth rule available is throwing an exception" in {
          assertNotMatchRule(
            settings = GroupsRulesSettings(
              groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                mode = WithoutGroupsMapping(authenticationRule.throwing, groups("g1"))
              ))
            ),
            loggedUser = Some(User.Id("user1")),
            preferredGroupId = None
          )
        }
      }
      "groups mapping is configured" when {
        "user cannot be authenticated by authentication with authorization rule" in {
          assertNotMatchRule(
            settings = GroupsRulesSettings(
              groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(
                createVariable("group_@{user}")(AlwaysRightConvertible.from(GroupIdLike.from)).toOption.get
              )),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                mode = WithGroupsMapping(SingleRule(authRule.rejecting), noGroupMappingFrom("group_user1"))
              ))
            ),
            loggedUser = None,
            preferredGroupId = None
          )
        }
        "user cannot be authorized by authentication with authorization rule" in {
          assertNotMatchRule(
            settings = GroupsRulesSettings(
              groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(
                createVariable("group_@{user}")(AlwaysRightConvertible.from(GroupIdLike.from)).toOption.get
              )),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                mode = WithGroupsMapping(
                  SeparateRules(
                    authenticationRule.matching(User.Id("user1")),
                    authorizationRule.rejecting
                  ),
                  noGroupMappingFrom("group_user1")
                )
              ))
            ),
            loggedUser = None,
            preferredGroupId = None
          )
        }
        "user cannot be authorized by authentication with authorization rule (simple groups mapping matching fails)" in {
          assertNotMatchRule(
            settings = GroupsRulesSettings(
              groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(
                createVariable("group_@{user}")(AlwaysRightConvertible.from(GroupIdLike.from)).toOption.get
              )),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                mode = WithGroupsMapping(
                  SeparateRules(
                    authenticationRule.matching(User.Id("user1")),
                    authorizationRule.matching(NonEmptyList.of(group("remote_group1")))
                  ),
                  noGroupMappingFrom("group_user1")
                )
              ))
            ),
            loggedUser = None,
            preferredGroupId = None
          )
        }
        "user cannot be authorized by authentication with authorization rule (advanced groups mapping matching fails)" in {
          assertNotMatchRule(
            settings = GroupsRulesSettings(
              groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(
                createVariable("group_@{user}")(AlwaysRightConvertible.from(GroupIdLike.from)).toOption.get
              )),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                mode = WithGroupsMapping(
                  SeparateRules(
                    authenticationRule.matching(User.Id("user1")),
                    authorizationRule.matching(NonEmptyList.of(group("remote_group2")))
                  ),
                  groupMapping(
                    Mapping(group("group_user1"), UniqueNonEmptyList.of(GroupIdLike.from("remote_group_1"))),
                    Mapping(group("group_user2"), UniqueNonEmptyList.of(GroupIdLike.from("remote_group_2")))
                  )
                )
              ))
            ),
            loggedUser = None,
            preferredGroupId = None
          )
        }
        "user cannot be authorized by authentication with authorization rule (advanced groups mapping with wildcard matching fails)" in {
          assertNotMatchRule(
            settings = GroupsRulesSettings(
              groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(
                createVariable("group_@{user}")(AlwaysRightConvertible.from(GroupIdLike.from)).toOption.get
              )),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                mode = WithGroupsMapping(
                  SeparateRules(
                    authenticationRule.matching(User.Id("user1")),
                    authorizationRule.matching(NonEmptyList.of(group("remote_group2")))
                  ),
                  groupMapping(
                    Mapping(group("group_user1"), UniqueNonEmptyList.of(GroupIdLike.from("*1"))),
                    Mapping(group("group_user2"), UniqueNonEmptyList.of(GroupIdLike.from("*2")))
                  )
                )
              ))
            ),
            loggedUser = None,
            preferredGroupId = None
          )
        }
      }
    }
    "not match because of the forbidden group present" when {
      "groups mapping is not configured" when {
        "user is logged in" when {
          "user ID is matched by user definition with full username" when {
            "authentication rule also matches and case sensitivity is configured" in {
              assertNotMatchRule(
                settings = GroupsRulesSettings(
                  groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
                  usersDefinitions = NonEmptyList.of(UserDef(
                    usernames = userIdPatterns("user1"),
                    mode = WithoutGroupsMapping(
                      authenticationRule.matching(User.Id("user1")),
                      groups("g1")
                    )
                  ))
                ),
                loggedUser = None,
                preferredGroupId = None
              )
            }
            "authentication rule matches and case insensitivity is configured" in {
              assertNotMatchRule(
                settings = GroupsRulesSettings(
                  groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
                  usersDefinitions = NonEmptyList.of(UserDef(
                    usernames = userIdPatterns("user1"),
                    mode = WithoutGroupsMapping(
                      authenticationRule.matching(User.Id("User1")),
                      groups("g1")
                    )
                  ))
                ),
                loggedUser = None,
                preferredGroupId = None,
                caseSensitivity = CaseSensitivity.Disabled
              )
            }
          }
          "user ID is matched by user definition with username with wildcard" when {
            "authentication rule matches and and case sensitivity is configured" in {
              assertNotMatchRule(
                settings = GroupsRulesSettings(
                  groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
                  usersDefinitions = NonEmptyList.of(UserDef(
                    usernames = userIdPatterns("u*"),
                    mode = WithoutGroupsMapping(
                      authenticationRule.matching(User.Id("user1")),
                      groups("g1")
                    )
                  ))
                ),
                loggedUser = None,
                preferredGroupId = None
              )
            }
            "authentication rule matches and and case insensitivity is configured" in {
              assertNotMatchRule(
                settings = GroupsRulesSettings(
                  groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
                  usersDefinitions = NonEmptyList.of(UserDef(
                    usernames = userIdPatterns("u*"),
                    mode = WithoutGroupsMapping(
                      authenticationRule.matching(User.Id("User1")),
                      groups("g1")
                    )
                  ))
                ),
                loggedUser = None,
                preferredGroupId = None,
                caseSensitivity = CaseSensitivity.Disabled
              )
            }
          }
        }
        "user is not logged in" when {
          "user ID is matched by user definition with full username" when {
            "authentication rule matches and preferred group is used" in {
              assertNotMatchRule(
                settings = GroupsRulesSettings(
                  groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
                  usersDefinitions = NonEmptyList.of(
                    UserDef(
                      usernames = userIdPatterns("user2"),
                      mode = WithoutGroupsMapping(
                        authenticationRule.rejecting,
                        groups("g1", "g2")
                      )
                    ),
                    UserDef(
                      usernames = userIdPatterns("user1"),
                      mode = WithoutGroupsMapping(
                        authenticationRule.matching(User.Id("user1")),
                        groups("g1")
                      )
                    )
                  )
                ),
                loggedUser = None,
                preferredGroupId = Some(GroupId("g1"))
              )
            }
          }
        }
      }
      "groups mapping is configured" when {
        "one authentication with authorization rule is used" when {
          "user can be matched and user can be authorized in external system and locally (simple groups mapping)" in {
            assertNotMatchRule(
              settings = GroupsRulesSettings(
                groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
                usersDefinitions = NonEmptyList.of(
                  UserDef(
                    usernames = userIdPatterns("user2"),
                    mode = WithoutGroupsMapping(authenticationRule.rejecting, groups("g1", "g2"))
                  ),
                  UserDef(
                    usernames = userIdPatterns("user1"),
                    mode = WithGroupsMapping(
                      SingleRule(authRule.matching(User.Id("user1"), NonEmptyList.of(group("remote_group")))),
                      noGroupMappingFrom("g1")
                    )
                  )
                )
              ),
              loggedUser = None,
              preferredGroupId = None
            )
          }
          "user can be matched and user can be authorized in external system and locally (advanced groups mapping)" in {
            assertNotMatchRule(
              settings = GroupsRulesSettings(
                groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
                usersDefinitions = NonEmptyList.of(
                  UserDef(
                    usernames = userIdPatterns("user2"),
                    mode = WithoutGroupsMapping(authenticationRule.rejecting, groups("g1", "g2"))
                  ),
                  UserDef(
                    usernames = userIdPatterns("user1"),
                    mode = WithGroupsMapping(
                      SingleRule(authRule.matching(User.Id("user1"), NonEmptyList.of(group("remote_group")))),
                      groupMapping(Mapping(group("g1"), UniqueNonEmptyList.of(GroupIdLike.from("remote_group"))))
                    )
                  )
                )
              ),
              loggedUser = None,
              preferredGroupId = None
            )
          }
          "user can be matched and user can be authorized in external system and locally (advanced groups mapping with wildcard)" in {
            assertNotMatchRule(
              settings = GroupsRulesSettings(
                groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
                usersDefinitions = NonEmptyList.of(
                  UserDef(
                    usernames = userIdPatterns("user2"),
                    mode = WithoutGroupsMapping(authenticationRule.rejecting, groups("g1", "g2"))
                  ),
                  UserDef(
                    usernames = userIdPatterns("user1"),
                    mode = WithGroupsMapping(
                      SingleRule(authRule.matching(User.Id("user1"), NonEmptyList.of(group("remote_group_1")))),
                      groupMapping(Mapping(group("g1"), UniqueNonEmptyList.of(GroupIdLike.from("*1"))))
                    )
                  )
                )
              ),
              loggedUser = None,
              preferredGroupId = None
            )
          }
        }
        "separate authentication and authorization rules are used" when {
          "user can be matched and user can be authorized in external system and locally" in {
            assertNotMatchRule(
              settings = GroupsRulesSettings(
                groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
                usersDefinitions = NonEmptyList.of(
                  UserDef(
                    usernames = userIdPatterns("user2"),
                    mode = WithoutGroupsMapping(authenticationRule.rejecting, groups("g1", "g2"))
                  ),
                  UserDef(
                    usernames = userIdPatterns("user1"),
                    mode = WithGroupsMapping(
                      SeparateRules(
                        authenticationRule.matching(User.Id("user1")),
                        authorizationRule.matching(NonEmptyList.of(group("remote_group")))
                      ),
                      noGroupMappingFrom("g1")
                    )
                  )
                )
              ),
              loggedUser = None,
              preferredGroupId = None
            )
          }
        }
      }
    }
    "match because no forbidden group present" when {
      "groups mapping is not configured" when {
        "user is logged in" when {
          "user ID is matched by user definition with full username" when {
            "authentication rule also matches and case sensitivity is configured" in {
              assertMatchRule(
                settings = GroupsRulesSettings(
                  groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
                  usersDefinitions = NonEmptyList.of(UserDef(
                    usernames = userIdPatterns("user1"),
                    mode = WithoutGroupsMapping(
                      authenticationRule.matching(User.Id("user1")),
                      groups("h1")
                    )
                  ))
                ),
                loggedUser = None,
                preferredGroupId = None
              )(
                blockContextAssertion = defaultOutputBlockContextAssertion(
                  user = User.Id("user1"),
                  group = GroupId("h1"),
                  availableGroups = UniqueList.of(group("h1"))
                )
              )
            }
            "authentication rule also matches and case insensitivity is configured" in {
              assertMatchRule(
                settings = GroupsRulesSettings(
                  groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
                  usersDefinitions = NonEmptyList.of(UserDef(
                    usernames = userIdPatterns("user1"),
                    mode = WithoutGroupsMapping(
                      authenticationRule.matching(User.Id("User1")),
                      groups("h1")
                    )
                  ))
                ),
                loggedUser = None,
                preferredGroupId = None,
                caseSensitivity = CaseSensitivity.Disabled
              )(
                blockContextAssertion = defaultOutputBlockContextAssertion(
                  user = User.Id("User1"),
                  group = GroupId("h1"),
                  availableGroups = UniqueList.of(group("h1"))
                )
              )
            }
          }
          "user ID is matched by user definition with username with wildcard" when {
            "authentication rule also matches and and case sensitivity is configured" in {
              assertMatchRule(
                settings = GroupsRulesSettings(
                  groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
                  usersDefinitions = NonEmptyList.of(UserDef(
                    usernames = userIdPatterns("u*"),
                    mode = WithoutGroupsMapping(
                      authenticationRule.matching(User.Id("user1")),
                      groups("h1")
                    )
                  ))
                ),
                loggedUser = None,
                preferredGroupId = None
              )(
                blockContextAssertion = defaultOutputBlockContextAssertion(
                  user = User.Id("user1"),
                  group = GroupId("h1"),
                  availableGroups = UniqueList.of(group("h1"))
                )
              )
            }
            "authentication rule also matches and and case insensitivity is configured" in {
              assertMatchRule(
                settings = GroupsRulesSettings(
                  groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
                  usersDefinitions = NonEmptyList.of(UserDef(
                    usernames = userIdPatterns("u*"),
                    mode = WithoutGroupsMapping(
                      authenticationRule.matching(User.Id("User1")),
                      groups("h1")
                    )
                  ))
                ),
                loggedUser = None,
                preferredGroupId = None,
                caseSensitivity = CaseSensitivity.Disabled
              )(
                blockContextAssertion = defaultOutputBlockContextAssertion(
                  user = User.Id("User1"),
                  group = GroupId("h1"),
                  availableGroups = UniqueList.of(group("h1"))
                )
              )
            }
          }
        }
        "user is not logged in" when {
          "user ID is matched by user definition with full username" when {
            "authentication rule also matches and preferred group is used" in {
              assertMatchRule(
                settings = GroupsRulesSettings(
                  groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
                  usersDefinitions = NonEmptyList.of(
                    UserDef(
                      usernames = userIdPatterns("user2"),
                      mode = WithoutGroupsMapping(
                        authenticationRule.rejecting,
                        groups("h1", "h2")
                      )
                    ),
                    UserDef(
                      usernames = userIdPatterns("user1"),
                      mode = WithoutGroupsMapping(
                        authenticationRule.matching(User.Id("user1")),
                        groups("h1")
                      )
                    )
                  )
                ),
                loggedUser = None,
                preferredGroupId = Some(GroupId("h1"))
              )(
                blockContextAssertion = defaultOutputBlockContextAssertion(
                  user = User.Id("user1"),
                  group = GroupId("h1"),
                  availableGroups = UniqueList.of(group("h1"))
                )
              )
            }
          }
        }
      }
      "groups mapping is configured" when {
        "one authentication with authorization rule is used" when {
          "user can be matched and user can be authorized in external system and locally (simple groups mapping)" in {
            assertMatchRule(
              settings = GroupsRulesSettings(
                groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
                usersDefinitions = NonEmptyList.of(
                  UserDef(
                    usernames = userIdPatterns("user2"),
                    mode = WithoutGroupsMapping(authenticationRule.rejecting, groups("h1", "h2"))
                  ),
                  UserDef(
                    usernames = userIdPatterns("user1"),
                    mode = WithGroupsMapping(
                      SingleRule(authRule.matching(User.Id("user1"), NonEmptyList.of(group("remote_group")))),
                      noGroupMappingFrom("h1")
                    )
                  )
                )
              ),
              loggedUser = None,
              preferredGroupId = None
            )(
              blockContextAssertion = defaultOutputBlockContextAssertion(
                user = User.Id("user1"),
                group = GroupId("h1"),
                availableGroups = UniqueList.of(group("h1"))
              )
            )
          }
          "user can be matched and user can be authorized in external system and locally (advanced groups mapping)" in {
            assertMatchRule(
              settings = GroupsRulesSettings(
                groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
                usersDefinitions = NonEmptyList.of(
                  UserDef(
                    usernames = userIdPatterns("user2"),
                    mode = WithoutGroupsMapping(authenticationRule.rejecting, groups("h1", "h2"))
                  ),
                  UserDef(
                    usernames = userIdPatterns("user1"),
                    mode = WithGroupsMapping(
                      SingleRule(authRule.matching(User.Id("user1"), NonEmptyList.of(group("remote_group")))),
                      groupMapping(Mapping(group("h1"), UniqueNonEmptyList.of(GroupIdLike.from("remote_group"))))
                    )
                  )
                )
              ),
              loggedUser = None,
              preferredGroupId = None
            )(
              blockContextAssertion = defaultOutputBlockContextAssertion(
                user = User.Id("user1"),
                group = GroupId("h1"),
                availableGroups = UniqueList.of(group("h1"))
              )
            )
          }
          "user can be matched and user can be authorized in external system and locally (advanced groups mapping with wildcard)" in {
            assertMatchRule(
              settings = GroupsRulesSettings(
                groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
                usersDefinitions = NonEmptyList.of(
                  UserDef(
                    usernames = userIdPatterns("user2"),
                    mode = WithoutGroupsMapping(authenticationRule.rejecting, groups("h1", "h2"))
                  ),
                  UserDef(
                    usernames = userIdPatterns("user1"),
                    mode = WithGroupsMapping(
                      SingleRule(authRule.matching(User.Id("user1"), NonEmptyList.of(group("remote_group_1")))),
                      groupMapping(Mapping(group("h1"), UniqueNonEmptyList.of(GroupIdLike.from("*1"))))
                    )
                  )
                )
              ),
              loggedUser = None,
              preferredGroupId = None
            )(
              blockContextAssertion = defaultOutputBlockContextAssertion(
                user = User.Id("user1"),
                group = GroupId("h1"),
                availableGroups = UniqueList.of(group("h1"))
              )
            )
          }
        }
        "separate authentication and authorization rules are used" when {
          "user can be matched and user can be authorized in external system and locally" in {
            assertMatchRule(
              settings = GroupsRulesSettings(
                groupIds = ResolvableGroupIds(UniqueNonEmptyList.of(AlreadyResolved(GroupId("g1").nel))),
                usersDefinitions = NonEmptyList.of(
                  UserDef(
                    usernames = userIdPatterns("user2"),
                    mode = WithoutGroupsMapping(authenticationRule.rejecting, groups("h1", "h2"))
                  ),
                  UserDef(
                    usernames = userIdPatterns("user1"),
                    mode = WithGroupsMapping(
                      SeparateRules(
                        authenticationRule.matching(User.Id("user1")),
                        authorizationRule.matching(NonEmptyList.of(group("remote_group")))
                      ),
                      noGroupMappingFrom("h1")
                    )
                  )
                )
              ),
              loggedUser = None,
              preferredGroupId = None
            )(
              blockContextAssertion = defaultOutputBlockContextAssertion(
                user = User.Id("user1"),
                group = GroupId("h1"),
                availableGroups = UniqueList.of(group("h1"))
              )
            )
          }
        }
      }
    }
  }
}
