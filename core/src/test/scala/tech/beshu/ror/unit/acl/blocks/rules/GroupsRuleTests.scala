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
import org.scalamock.scalatest.MockFactory
import org.scalatest.Inside
import org.scalatest.Inside.inside
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContextUpdater.CurrentUserMetadataRequestBlockContextUpdater
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.Mode.WithGroupsMapping.Auth.{SeparateRules, SingleRule}
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.Mode.{WithGroupsMapping, WithoutGroupsMapping}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.GroupsRule
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.AuthenticationRule.EligibleUsersSupport
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule._
import tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation.{AuthenticationImpersonationCustomSupport, AuthorizationImpersonationCustomSupport}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.AlwaysRightConvertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariableCreator.createMultiResolvableVariableFrom
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.domain.User.UserIdPattern
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.providers.{EnvVarsProvider, OsEnvVarsProvider}
import tech.beshu.ror.unit.acl.blocks.rules.GroupsRuleTests.RuleCreator
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.UserIdEq
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

import scala.concurrent.duration._
import scala.language.postfixOps

class GroupsRuleTests extends AnyWordSpec with Inside with BlockContextAssertion {

  implicit val provider: EnvVarsProvider = OsEnvVarsProvider

  val rc: RuleCreator = (settings: GroupsRule.Settings, caseSensitivity: UserIdCaseMappingEquality) => new GroupsRule(settings, caseSensitivity)

  import tech.beshu.ror.unit.acl.blocks.rules.GroupsRuleTests._

  "A GroupsRule" should {
    "match" when {
      "groups mapping is not configured" when {
        "user is logged in" when {
          "user ID is matched by user definition with full username" when {
            "authentication rule also matches and case sensitivity is configured" in {
              assertMatchRule(
                settings = GroupsRule.Settings(
                  groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
                  usersDefinitions = NonEmptyList.of(UserDef(
                    usernames = userIdPatterns("user1"),
                    mode = WithoutGroupsMapping(
                      authenticationRule.matching(User.Id("user1")),
                      groups("g1", "g2")
                    )
                  ))
                ),
                loggedUser = None,
                preferredGroup = None
              )(
                blockContextAssertion = defaultOutputBlockContextAssertion(
                  user = User.Id("user1"),
                  group = groupFrom("g1"),
                  availableGroups = UniqueList.of(groupFrom("g1"))
                )
              )(rc)
            }
            "authentication rule also matches and case insensitivity is configured" in {
              assertMatchRule(
                settings = GroupsRule.Settings(
                  groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
                  usersDefinitions = NonEmptyList.of(UserDef(
                    usernames = userIdPatterns("user1"),
                    mode = WithoutGroupsMapping(
                      authenticationRule.matching(User.Id("User1")),
                      groups("g1", "g2")
                    )
                  ))
                ),
                loggedUser = None,
                preferredGroup = None,
                caseSensitivity = false
              )(
                blockContextAssertion = defaultOutputBlockContextAssertion(
                  user = User.Id("User1"),
                  group = groupFrom("g1"),
                  availableGroups = UniqueList.of(groupFrom("g1"))
                )
              )(rc)
            }
          }
          "user ID is matched by user definition with username with wildcard" when {
            "authentication rule also matches and and case sensitivity is configured" in {
              assertMatchRule(
                settings = GroupsRule.Settings(
                  groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
                  usersDefinitions = NonEmptyList.of(UserDef(
                    usernames = userIdPatterns("u*"),
                    mode = WithoutGroupsMapping(
                      authenticationRule.matching(User.Id("user1")),
                      groups("g1", "g2")
                    )
                  ))
                ),
                loggedUser = None,
                preferredGroup = None
              )(
                blockContextAssertion = defaultOutputBlockContextAssertion(
                  user = User.Id("user1"),
                  group = groupFrom("g1"),
                  availableGroups = UniqueList.of(groupFrom("g1"))
                )
              )(rc)
            }
            "authentication rule also matches and and case insensitivity is configured" in {
              assertMatchRule(
                settings = GroupsRule.Settings(
                  groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
                  usersDefinitions = NonEmptyList.of(UserDef(
                    usernames = userIdPatterns("u*"),
                    mode = WithoutGroupsMapping(
                      authenticationRule.matching(User.Id("User1")),
                      groups("g1", "g2")
                    )
                  ))
                ),
                loggedUser = None,
                preferredGroup = None,
                caseSensitivity = false
              )(
                blockContextAssertion = defaultOutputBlockContextAssertion(
                  user = User.Id("User1"),
                  group = groupFrom("g1"),
                  availableGroups = UniqueList.of(groupFrom("g1"))
                )
              )(rc)
            }
          }
        }
        "user is not logged in" when {
          "user ID is matched by user definition with full username" when {
            "authentication rule also matches and preferred group is used" in {
              assertMatchRule(
                settings = GroupsRule.Settings(
                  groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
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
                preferredGroup = Some(groupFrom("g1"))
              )(
                blockContextAssertion = defaultOutputBlockContextAssertion(
                  user = User.Id("user1"),
                  group = groupFrom("g1"),
                  availableGroups = UniqueList.of(groupFrom("g1"))
                )
              )(rc)
            }
          }
        }
      }
      "groups mapping is configured" when {
        "one authentication with authorization rule is used" when {
          "user can be matched and user can be authorized in external system and locally" in {
            assertMatchRule(
              settings = GroupsRule.Settings(
                groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
                usersDefinitions = NonEmptyList.of(
                  UserDef(
                    usernames = userIdPatterns("user2"),
                    mode = WithoutGroupsMapping(authenticationRule.rejecting, groups("g1", "g2"))
                  ),
                  UserDef(
                    usernames = userIdPatterns("user1"),
                    mode = WithGroupsMapping(
                      SingleRule(authRule.matching(User.Id("user1"), NonEmptyList.of(Group("remote_group")))),
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
                group = groupFrom("g1"),
                availableGroups = UniqueList.of(groupFrom("g1"))
              )
            )(rc)
          }
        }
        "separate authentication and authorization rules are used" when {
          "user can be matched and user can be authorized in external system and locally" in {
            assertMatchRule(
              settings = GroupsRule.Settings(
                groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
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
                        authorizationRule.matching(NonEmptyList.of(Group("remote_group")))
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
                group = groupFrom("g1"),
                availableGroups = UniqueList.of(groupFrom("g1"))
              )
            )(rc)
          }
        }
      }
    }
    "not match" when {
      "groups mapping is not configured" when {
        "no group can be resolved" in {
          assertNotMatchRule(
            settings = GroupsRule.Settings(
              groups = UniqueNonEmptyList.of(createMultiResolvableVariableFrom("group_@{user}")(AlwaysRightConvertible.from(Group.apply)).right.get),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                mode = WithoutGroupsMapping(authenticationRule.rejecting, groups("group_user1"))
              ))
            ),
            loggedUser = None,
            preferredGroup = None
          )(rc)
        }
        "resolved groups don't contain preferred group" in {
          assertNotMatchRule(
            settings = GroupsRule.Settings(
              groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                mode = WithoutGroupsMapping(authenticationRule.rejecting, groups("g1"))
              ))
            ),
            loggedUser = None,
            preferredGroup = Some(groupFrom("g2"))
          )(rc)
        }
        "there is no user definition for given logged user" in {
          assertNotMatchRule(
            settings = GroupsRule.Settings(
              groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                mode = WithoutGroupsMapping(authenticationRule.rejecting, groups("g1"))
              ))
            ),
            loggedUser = Some(User.Id("user2")),
            preferredGroup = None
          )(rc)
        }
        "there is no matching auth rule for given user" in {
          assertNotMatchRule(
            settings = GroupsRule.Settings(
              groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                mode = WithoutGroupsMapping(authenticationRule.rejecting, groups("g1"))
              ))
            ),
            loggedUser = Some(User.Id("user1")),
            preferredGroup = None
          )(rc)
        }
        "case sensitivity is configured, but authentication rule authenticates user with name with a capital letter at the beginning" in {
          assertNotMatchRule(
            settings = GroupsRule.Settings(
              groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("u*"),
                mode = WithoutGroupsMapping(authenticationRule.matching(User.Id("User1")), groups("g1"))
              ))
            ),
            loggedUser = None,
            preferredGroup = None
          )(rc)
        }
        "one auth rule available is throwing an exception" in {
          assertNotMatchRule(
            settings = GroupsRule.Settings(
              groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                mode = WithoutGroupsMapping(authenticationRule.throwing, groups("g1"))
              ))
            ),
            loggedUser = Some(User.Id("user1")),
            preferredGroup = None
          )(rc)
        }
      }
      "groups mapping is configured" when {
        "user cannot be authenticated by authentication with authorization rule" in {
          assertNotMatchRule(
            settings = GroupsRule.Settings(
              groups = UniqueNonEmptyList.of(createMultiResolvableVariableFrom("group_@{user}")(AlwaysRightConvertible.from(Group.apply)).right.get),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                mode = WithGroupsMapping(SingleRule(authRule.rejecting), noGroupMappingFrom("group_user1"))
              ))
            ),
            loggedUser = None,
            preferredGroup = None
          )(rc)
        }
        "user cannot be authorized by authentication with authorization rule" in {
          assertNotMatchRule(
            settings = GroupsRule.Settings(
              groups = UniqueNonEmptyList.of(createMultiResolvableVariableFrom("group_@{user}")(AlwaysRightConvertible.from(Group.apply)).right.get),
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
          )(rc)
        }
      }
    }
  }

  def defaultOutputBlockContextAssertion(user: User.Id, group: Group, availableGroups: UniqueList[Group]): BlockContext => Unit =
    (blockContext: BlockContext) => {
      assertBlockContext(
        loggedUser = Some(DirectlyLoggedUser(user)),
        currentGroup = Some(group),
        availableGroups = availableGroups
      )(blockContext)
    }
}

object GroupsRuleTests extends MockFactory {

  type RuleCreator = (GroupsRule.Settings, UserIdCaseMappingEquality) => GroupsRule

  def assertMatchRule(settings: GroupsRule.Settings,
                      loggedUser: Option[User.Id],
                      preferredGroup: Option[Group],
                      caseSensitivity: Boolean = true)
                     (blockContextAssertion: BlockContext => Unit)(ruleCreator: RuleCreator): Unit =
    assertRule(settings, loggedUser, preferredGroup, Some(blockContextAssertion), caseSensitivity)(ruleCreator)

  def assertNotMatchRule(settings: GroupsRule.Settings,
                         loggedUser: Option[User.Id],
                         preferredGroup: Option[Group],
                         caseSensitivity: Boolean = true)(ruleCreator: RuleCreator): Unit =
    assertRule(settings, loggedUser, preferredGroup, blockContextAssertion = None, caseSensitivity)(ruleCreator)

  def assertRule(settings: GroupsRule.Settings,
                 loggedUser: Option[User.Id],
                 preferredGroup: Option[Group],
                 blockContextAssertion: Option[BlockContext => Unit],
                 caseSensitivity: Boolean)(ruleCreator: RuleCreator): Unit = {
    val rule = ruleCreator(
      settings,
      if (caseSensitivity) UserIdEq.caseSensitive else UserIdEq.caseInsensitive
    )
    val requestContext = MockRequestContext.metadata.copy(
      headers = preferredGroup.map(_.value).map(v => new Header(Header.Name.currentGroup, v)).toSet[Header]
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
      UniqueNonEmptyList.unsafeFromList(
        (id :: ids.toList).map(str => UserIdPattern(NonEmptyString.unsafeFrom(str)))
      )
    )
  }

  def groups(g1: String, gs: String*): UniqueNonEmptyList[Group] = {
    UniqueNonEmptyList.of(groupFrom(g1), gs.map(groupFrom): _*)
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

    def matching(groups: NonEmptyList[Group]): AuthorizationRule = new AuthorizationRule with AuthorizationImpersonationCustomSupport {
      override val name: Rule.Name = Rule.Name("dummy-fulfilling")

      override protected def authorize[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = {
        Task.now(Fulfilled(blockContext.withUserMetadata(
          _.withAvailableGroups(UniqueList.fromList(groups.toList))
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

    def matching(user: User.Id, groups: NonEmptyList[Group]): AuthRule = new AuthRule with AuthenticationRule with AuthorizationRule with AuthorizationImpersonationCustomSupport with AuthenticationImpersonationCustomSupport {
      override val name: Rule.Name = Rule.Name("dummy-fulfilling")

      override val eligibleUsers: EligibleUsersSupport = EligibleUsersSupport.NotAvailable
      override val caseMappingEquality: UserIdCaseMappingEquality = UserIdEq.caseSensitive

      override protected def authenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] =
        Task.now(Fulfilled(blockContext.withUserMetadata(
          _.withLoggedUser(DirectlyLoggedUser(user))
        )))

      override protected def authorize[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] =
        Task.now(Fulfilled(blockContext.withUserMetadata(
          _.withAvailableGroups(UniqueList.fromList(groups.toList))
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
