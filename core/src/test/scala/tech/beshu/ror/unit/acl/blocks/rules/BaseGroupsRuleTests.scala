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
package tech.beshu.ror.unit.acl.blocks.rules

import cats.data.NonEmptyList
import eu.timepit.refined.auto._
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpecLike
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContextUpdater.CurrentUserMetadataRequestBlockContextUpdater
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.Mode.WithGroupsMapping.Auth.{SeparateRules, SingleRule}
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.Mode.{WithGroupsMapping, WithoutGroupsMapping}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.BaseGroupsRule
import tech.beshu.ror.accesscontrol.blocks.rules.BaseGroupsRule.{Settings => GroupsRulesSettings}
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.AuthenticationRule.EligibleUsersSupport
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule._
import tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation.{AuthenticationImpersonationCustomSupport, AuthorizationImpersonationCustomSupport}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.AlwaysRightConvertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariableCreator.createMultiResolvableVariableFrom
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.GroupLike.GroupName
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.domain.User.UserIdPattern
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.providers.{EnvVarsProvider, OsEnvVarsProvider}
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.UserIdEq
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

import scala.concurrent.duration._
import scala.language.postfixOps

trait BaseGroupsRuleTests extends AnyWordSpecLike with Inside with BlockContextAssertion {

  implicit val provider: EnvVarsProvider = OsEnvVarsProvider

  def createRule(settings: GroupsRulesSettings, caseSensitivity: UserIdCaseMappingEquality): BaseGroupsRule

  // Common tests

  "An AbstractGroupsRule" should {
    "not match" when {
      "groups mapping is not configured" when {
        "no group can be resolved" in {
          assertNotMatchRule(
            settings = GroupsRulesSettings(
              permittedGroups = ResolvablePermittedGroups(UniqueNonEmptyList.of(
                createMultiResolvableVariableFrom("group_@{user}")(AlwaysRightConvertible.from(GroupLike.from)).right.get
              )),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                mode = WithoutGroupsMapping(authenticationRule.rejecting, groups("group_user1"))
              ))
            ),
            loggedUser = None,
            preferredGroup = None
          )
        }
        "resolved groups don't contain preferred group" in {
          assertNotMatchRule(
            settings = GroupsRulesSettings(
              permittedGroups = ResolvablePermittedGroups(UniqueNonEmptyList.of(AlreadyResolved(GroupLike.from("g1").nel))),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                mode = WithoutGroupsMapping(authenticationRule.rejecting, groups("g1"))
              ))
            ),
            loggedUser = None,
            preferredGroup = Some(GroupName("g2"))
          )
        }
        "there is no user definition for given logged user" in {
          assertNotMatchRule(
            settings = GroupsRulesSettings(
              permittedGroups = ResolvablePermittedGroups(UniqueNonEmptyList.of(AlreadyResolved(GroupName("g1").nel))),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                mode = WithoutGroupsMapping(authenticationRule.rejecting, groups("g1"))
              ))
            ),
            loggedUser = Some(User.Id("user2")),
            preferredGroup = None
          )
        }
        "there is no matching auth rule for given user" in {
          assertNotMatchRule(
            settings = GroupsRulesSettings(
              permittedGroups = ResolvablePermittedGroups(UniqueNonEmptyList.of(AlreadyResolved(GroupName("g1").nel))),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                mode = WithoutGroupsMapping(authenticationRule.rejecting, groups("g1"))
              ))
            ),
            loggedUser = Some(User.Id("user1")),
            preferredGroup = None
          )
        }
        "case sensitivity is configured, but authentication rule authenticates user with name with a capital letter at the beginning" in {
          assertNotMatchRule(
            settings = GroupsRulesSettings(
              permittedGroups = ResolvablePermittedGroups(UniqueNonEmptyList.of(AlreadyResolved(GroupName("g1").nel))),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("u*"),
                mode = WithoutGroupsMapping(authenticationRule.matching(User.Id("User1")), groups("g1"))
              ))
            ),
            loggedUser = None,
            preferredGroup = None
          )
        }
        "one auth rule available is throwing an exception" in {
          assertNotMatchRule(
            settings = GroupsRulesSettings(
              permittedGroups = ResolvablePermittedGroups(UniqueNonEmptyList.of(AlreadyResolved(GroupName("g1").nel))),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                mode = WithoutGroupsMapping(authenticationRule.throwing, groups("g1"))
              ))
            ),
            loggedUser = Some(User.Id("user1")),
            preferredGroup = None
          )
        }
      }
      "groups mapping is configured" when {
        "user cannot be authenticated by authentication with authorization rule" in {
          assertNotMatchRule(
            settings = GroupsRulesSettings(
              permittedGroups = ResolvablePermittedGroups(UniqueNonEmptyList.of(
                createMultiResolvableVariableFrom("group_@{user}")(AlwaysRightConvertible.from(GroupLike.from)).right.get
              )),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                mode = WithGroupsMapping(SingleRule(authRule.rejecting), noGroupMappingFrom("group_user1"))
              ))
            ),
            loggedUser = None,
            preferredGroup = None
          )
        }
        "user cannot be authorized by authentication with authorization rule" in {
          assertNotMatchRule(
            settings = GroupsRulesSettings(
              permittedGroups = ResolvablePermittedGroups(UniqueNonEmptyList.of(
                createMultiResolvableVariableFrom("group_@{user}")(AlwaysRightConvertible.from(GroupLike.from)).right.get
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
            preferredGroup = None
          )
        }
      }
    }
    "match" when {
      "groups mapping is not configured" when {
        "user is logged in" when {
          "user ID is matched by user definition with full username" when {
            "authentication rule also matches and case sensitivity is configured" in {
              assertMatchRule(
                settings = GroupsRulesSettings(
                  permittedGroups = ResolvablePermittedGroups(UniqueNonEmptyList.of(AlreadyResolved(GroupName("g1").nel))),
                  usersDefinitions = NonEmptyList.of(UserDef(
                    usernames = userIdPatterns("user1"),
                    mode = WithoutGroupsMapping(
                      authenticationRule.matching(User.Id("user1")),
                      groups("g1")
                    )
                  ))
                ),
                loggedUser = None,
                preferredGroup = None
              )(
                blockContextAssertion = defaultOutputBlockContextAssertion(
                  user = User.Id("user1"),
                  group = GroupName("g1"),
                  availableGroups = UniqueList.of(GroupName("g1"))
                )
              )
            }
            "authentication rule also matches and case insensitivity is configured" in {
              assertMatchRule(
                settings = GroupsRulesSettings(
                  permittedGroups = ResolvablePermittedGroups(UniqueNonEmptyList.of(AlreadyResolved(GroupName("g1").nel))),
                  usersDefinitions = NonEmptyList.of(UserDef(
                    usernames = userIdPatterns("user1"),
                    mode = WithoutGroupsMapping(
                      authenticationRule.matching(User.Id("User1")),
                      groups("g1")
                    )
                  ))
                ),
                loggedUser = None,
                preferredGroup = None,
                caseSensitivity = false
              )(
                blockContextAssertion = defaultOutputBlockContextAssertion(
                  user = User.Id("User1"),
                  group = GroupName("g1"),
                  availableGroups = UniqueList.of(GroupName("g1"))
                )
              )
            }
          }
          "user ID is matched by user definition with username with wildcard" when {
            "authentication rule also matches and and case sensitivity is configured" in {
              assertMatchRule(
                settings = GroupsRulesSettings(
                  permittedGroups = ResolvablePermittedGroups(UniqueNonEmptyList.of(AlreadyResolved(GroupName("g1").nel))),
                  usersDefinitions = NonEmptyList.of(UserDef(
                    usernames = userIdPatterns("u*"),
                    mode = WithoutGroupsMapping(
                      authenticationRule.matching(User.Id("user1")),
                      groups("g1")
                    )
                  ))
                ),
                loggedUser = None,
                preferredGroup = None
              )(
                blockContextAssertion = defaultOutputBlockContextAssertion(
                  user = User.Id("user1"),
                  group = GroupName("g1"),
                  availableGroups = UniqueList.of(GroupName("g1"))
                )
              )
            }
            "authentication rule also matches and and case insensitivity is configured" in {
              assertMatchRule(
                settings = GroupsRulesSettings(
                  permittedGroups = ResolvablePermittedGroups(UniqueNonEmptyList.of(AlreadyResolved(GroupName("g1").nel))),
                  usersDefinitions = NonEmptyList.of(UserDef(
                    usernames = userIdPatterns("u*"),
                    mode = WithoutGroupsMapping(
                      authenticationRule.matching(User.Id("User1")),
                      groups("g1")
                    )
                  ))
                ),
                loggedUser = None,
                preferredGroup = None,
                caseSensitivity = false
              )(
                blockContextAssertion = defaultOutputBlockContextAssertion(
                  user = User.Id("User1"),
                  group = GroupName("g1"),
                  availableGroups = UniqueList.of(GroupName("g1"))
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
                  permittedGroups = ResolvablePermittedGroups(UniqueNonEmptyList.of(AlreadyResolved(GroupName("g1").nel))),
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
                preferredGroup = Some(GroupName("g1"))
              )(
                blockContextAssertion = defaultOutputBlockContextAssertion(
                  user = User.Id("user1"),
                  group = GroupName("g1"),
                  availableGroups = UniqueList.of(GroupName("g1"))
                )
              )
            }
          }
        }
      }
      "groups mapping is configured" when {
        "one authentication with authorization rule is used" when {
          "user can be matched and user can be authorized in external system and locally" in {
            assertMatchRule(
              settings = GroupsRulesSettings(
                permittedGroups = ResolvablePermittedGroups(UniqueNonEmptyList.of(AlreadyResolved(GroupName("g1").nel))),
                usersDefinitions = NonEmptyList.of(
                  UserDef(
                    usernames = userIdPatterns("user2"),
                    mode = WithoutGroupsMapping(authenticationRule.rejecting, groups("g1", "g2"))
                  ),
                  UserDef(
                    usernames = userIdPatterns("user1"),
                    mode = WithGroupsMapping(
                      SingleRule(authRule.matching(User.Id("user1"), NonEmptyList.of(GroupName("remote_group")))),
                      noGroupMappingFrom("g1")
                    )
                  )
                )
              ),
              loggedUser = None,
              preferredGroup = None
            )(
              blockContextAssertion = defaultOutputBlockContextAssertion(
                user = User.Id("user1"),
                group = GroupName("g1"),
                availableGroups = UniqueList.of(GroupName("g1"))
              )
            )
          }
        }
        "separate authentication and authorization rules are used" when {
          "user can be matched and user can be authorized in external system and locally" in {
            assertMatchRule(
              settings = GroupsRulesSettings(
                permittedGroups = ResolvablePermittedGroups(UniqueNonEmptyList.of(AlreadyResolved(GroupName("g1").nel))),
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
                        authorizationRule.matching(NonEmptyList.of(GroupName("remote_group")))
                      ),
                      noGroupMappingFrom("g1")
                    )
                  )
                )
              ),
              loggedUser = None,
              preferredGroup = None
            )(
              blockContextAssertion = defaultOutputBlockContextAssertion(
                user = User.Id("user1"),
                group = GroupName("g1"),
                availableGroups = UniqueList.of(GroupName("g1"))
              )
            )
          }
        }
      }
    }
  }

  def assertMatchRule(settings: GroupsRulesSettings,
                      loggedUser: Option[User.Id],
                      preferredGroup: Option[GroupName],
                      caseSensitivity: Boolean = true)
                     (blockContextAssertion: BlockContext => Unit): Unit =
    assertRule(settings, loggedUser, preferredGroup, Some(blockContextAssertion), caseSensitivity)

  def assertNotMatchRule(settings: GroupsRulesSettings,
                         loggedUser: Option[User.Id],
                         preferredGroup: Option[GroupName],
                         caseSensitivity: Boolean = true): Unit =
    assertRule(settings, loggedUser, preferredGroup, blockContextAssertion = None, caseSensitivity)

  def assertRule(settings: GroupsRulesSettings,
                 loggedUser: Option[User.Id],
                 preferredGroup: Option[GroupName],
                 blockContextAssertion: Option[BlockContext => Unit],
                 caseSensitivity: Boolean): Unit = {
    val rule = createRule(
      settings,
      if (caseSensitivity) UserIdEq.caseSensitive else UserIdEq.caseInsensitive
    )
    val requestContext = MockRequestContext.metadata.copy(
      headers = preferredGroup.map(_.toCurrentGroupHeader).toSet
    )
    val blockContext = CurrentUserMetadataRequestBlockContext(
      requestContext,
      loggedUser match {
        case Some(user) => UserMetadata.from(requestContext).withLoggedUser(DirectlyLoggedUser(user))
        case None => UserMetadata.from(requestContext)
      },
      Set.empty,
      List.empty
    )
    val result = rule.check(blockContext).runSyncUnsafe(1 second)
    blockContextAssertion match {
      case Some(assertOutputBlockContext) =>
        inside(result) { case Fulfilled(outBlockContext) =>
          assertOutputBlockContext(outBlockContext)
        }
      case None =>
        result should be(Rejected())
    }
  }

  def userIdPatterns(id: String, ids: String*): UserIdPatterns = {
    UserIdPatterns(
      UniqueNonEmptyList.unsafeFromTraversable(
        (id :: ids.toList).map(str => UserIdPattern(NonEmptyString.unsafeFrom(str)))
      )
    )
  }

  def groups(g1: NonEmptyString, gs: NonEmptyString*): UniqueNonEmptyList[GroupName] = {
    UniqueNonEmptyList.of(GroupName(g1), gs.map(GroupName.apply): _*)
  }

  def defaultOutputBlockContextAssertion(user: User.Id, 
                                         group: GroupName,
                                         availableGroups: UniqueList[GroupName]): BlockContext => Unit =
    (blockContext: BlockContext) => {
      assertBlockContext(
        loggedUser = Some(DirectlyLoggedUser(user)),
        currentGroup = Some(group),
        availableGroups = availableGroups
      )(blockContext)
    }


  object authenticationRule {

    def matching(user: User.Id): AuthenticationRule = new AuthenticationRule with AuthenticationImpersonationCustomSupport {
      override val name: Rule.Name = Rule.Name("dummy-fulfilling")
      override val caseMappingEquality: UserIdCaseMappingEquality = UserIdEq.caseSensitive
      override val eligibleUsers: EligibleUsersSupport = EligibleUsersSupport.NotAvailable

      override protected def authenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] =
        Task.now(Fulfilled(blockContext.withUserMetadata(_.withLoggedUser(DirectlyLoggedUser(user)))))
    }

    val rejecting: AuthenticationRule = new AuthenticationRule with AuthenticationImpersonationCustomSupport {
      override val name: Rule.Name = Rule.Name("dummy-rejecting")
      override val caseMappingEquality: UserIdCaseMappingEquality = UserIdEq.caseSensitive
      override val eligibleUsers: EligibleUsersSupport = EligibleUsersSupport.NotAvailable

      override protected def authenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] =
        Task.now(Rejected())
    }

    val throwing: AuthenticationRule = new AuthenticationRule with AuthenticationImpersonationCustomSupport {
      override val name: Rule.Name = Rule.Name("dummy-throwing")
      override val eligibleUsers: EligibleUsersSupport = EligibleUsersSupport.NotAvailable
      override val caseMappingEquality: UserIdCaseMappingEquality = UserIdEq.caseSensitive

      override protected def authenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] =
        Task.raiseError(new Exception("Sth went wrong"))
    }
  }


  object authorizationRule {

    def matching(groups: NonEmptyList[GroupName]): AuthorizationRule = new AuthorizationRule with AuthorizationImpersonationCustomSupport {
      override val name: Rule.Name = Rule.Name("dummy-fulfilling")

      override protected def authorize[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = {
        Task.now(Fulfilled(blockContext.withUserMetadata(
          _.withAvailableGroups(UniqueList.fromTraversable(groups.toList))
        )))
      }
    }

    val rejecting: AuthorizationRule = new AuthorizationRule with AuthorizationImpersonationCustomSupport {
      override val name: Rule.Name = Rule.Name("dummy-rejecting")

      override protected def authorize[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] =
        Task.now(Rejected())
    }
  }

  object authRule {

    def matching(user: User.Id, groups: NonEmptyList[GroupName]): AuthRule = new AuthRule with AuthenticationRule with AuthorizationRule with AuthorizationImpersonationCustomSupport with AuthenticationImpersonationCustomSupport {
      override val name: Rule.Name = Rule.Name("dummy-fulfilling")

      override val eligibleUsers: EligibleUsersSupport = EligibleUsersSupport.NotAvailable
      override val caseMappingEquality: UserIdCaseMappingEquality = UserIdEq.caseSensitive

      override protected def authenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] =
        Task.now(Fulfilled(blockContext.withUserMetadata(
          _.withLoggedUser(DirectlyLoggedUser(user))
        )))

      override protected def authorize[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] =
        Task.now(Fulfilled(blockContext.withUserMetadata(
          _.withAvailableGroups(UniqueList.fromTraversable(groups.toList))
        )))
    }

    val rejecting: AuthRule = new AuthRule with AuthenticationRule with AuthorizationRule with AuthorizationImpersonationCustomSupport with AuthenticationImpersonationCustomSupport {
      override val name: Rule.Name = Rule.Name("dummy-rejecting")

      override val eligibleUsers: EligibleUsersSupport = EligibleUsersSupport.NotAvailable
      override val caseMappingEquality: UserIdCaseMappingEquality = UserIdEq.caseSensitive

      override protected def authenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] =
        Task.now(Rejected())

      override protected def authorize[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] =
        Task.now(Rejected())
    }
  }
}
