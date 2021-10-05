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

import cats.Eq
import cats.data.NonEmptyList
import eu.timepit.refined.auto._
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContextUpdater.CurrentUserMetadataRequestBlockContextUpdater
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.Mode.WithGroupsMapping.Auth.{SingleRule, SeparateRules}
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.Mode.{WithGroupsMapping, WithoutGroupsMapping}
import tech.beshu.ror.accesscontrol.blocks.definitions.{ImpersonatorDef, UserDef}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule.{EligibleUsersSupport, UserExistence}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthRule, AuthenticationRule, AuthorizationRule}
import tech.beshu.ror.accesscontrol.blocks.rules.{GroupsRule, Rule}
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
import tech.beshu.ror.unit.acl.blocks.rules.GroupsRuleTests._
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.UserIdEq
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

import scala.concurrent.duration._
import scala.language.postfixOps

class GroupsRuleTests extends AnyWordSpec with Inside with BlockContextAssertion {

  implicit val provider: EnvVarsProvider = OsEnvVarsProvider

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
                    groupMappings = groups("g1", "g2"),
                    mode = WithoutGroupsMapping(authenticationRule.matching(User.Id("user1")))
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
              )
            }
            "authentication rule also matches and case insensitivity is configured" in {
              assertMatchRule(
                settings = GroupsRule.Settings(
                  groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
                  usersDefinitions = NonEmptyList.of(UserDef(
                    usernames = userIdPatterns("user1"),
                    groupMappings = groups("g1", "g2"),
                    mode = WithoutGroupsMapping(authenticationRule.matching(User.Id("User1")))
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
              )
            }
          }
          "user ID is matched by user definition with username with wildcard" when {
            "authentication rule also matches and and case sensitivity is configured" in {
              assertMatchRule(
                settings = GroupsRule.Settings(
                  groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
                  usersDefinitions = NonEmptyList.of(UserDef(
                    usernames = userIdPatterns("u*"),
                    groupMappings = groups("g1", "g2"),
                    mode = WithoutGroupsMapping(authenticationRule.matching(User.Id("user1")))
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
              )
            }
            "authentication rule also matches and and case insensitivity is configured" in {
              assertMatchRule(
                settings = GroupsRule.Settings(
                  groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
                  usersDefinitions = NonEmptyList.of(UserDef(
                    usernames = userIdPatterns("u*"),
                    groupMappings = groups("g1", "g2"),
                    mode = WithoutGroupsMapping(authenticationRule.matching(User.Id("User1")))
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
              )
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
                      groupMappings = groups("g1", "g2"),
                      mode = WithoutGroupsMapping(authenticationRule.rejecting)
                    ),
                    UserDef(
                      usernames = userIdPatterns("user1"),
                      groupMappings = groups("g1"),
                      mode = WithoutGroupsMapping(authenticationRule.matching(User.Id("user1")))
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
              )
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
                    groupMappings = groups("g1", "g2"),
                    mode = WithoutGroupsMapping(authenticationRule.rejecting)
                  ),
                  UserDef(
                    usernames = userIdPatterns("user1"),
                    groupMappings = groups("g1"),
                    mode = WithGroupsMapping(SingleRule(
                      authRule.matching(User.Id("user1"), NonEmptyList.of(Group("remote_group")))
                    ))
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
            )
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
                    groupMappings = groups("g1", "g2"),
                    mode = WithoutGroupsMapping(authenticationRule.rejecting)
                  ),
                  UserDef(
                    usernames = userIdPatterns("user1"),
                    groupMappings = groups("g1"),
                    mode = WithGroupsMapping(SeparateRules(
                      authenticationRule.matching(User.Id("user1")),
                      authorizationRule.matching(NonEmptyList.of(Group("remote_group")))
                    ))
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
            )
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
                groupMappings = groups("group_user1"),
                mode = WithoutGroupsMapping(authenticationRule.rejecting)
              ))
            ),
            loggedUser = None,
            preferredGroup = None
          )
        }
        "resolved groups don't contain preferred group" in {
          assertNotMatchRule(
            settings = GroupsRule.Settings(
              groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                groupMappings = groups("g1"),
                mode = WithoutGroupsMapping(authenticationRule.rejecting)
              ))
            ),
            loggedUser = None,
            preferredGroup = Some(groupFrom("g2"))
          )
        }
        "there is no user definition for given logged user" in {
          assertNotMatchRule(
            settings = GroupsRule.Settings(
              groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                groupMappings = groups("g1"),
                mode = WithoutGroupsMapping(authenticationRule.rejecting)
              ))
            ),
            loggedUser = Some(User.Id("user2")),
            preferredGroup = None
          )
        }
        "there is no matching auth rule for given user" in {
          assertNotMatchRule(
            settings = GroupsRule.Settings(
              groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                groupMappings = groups("g1"),
                mode = WithoutGroupsMapping(authenticationRule.rejecting)
              ))
            ),
            loggedUser = Some(User.Id("user1")),
            preferredGroup = None
          )
        }
        "case sensitivity is configured, but authentication rule authenticates user with name with a capital letter at the beginning" in {
          assertNotMatchRule(
            settings = GroupsRule.Settings(
              groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("u*"),
                groupMappings = groups("g1"),
                mode = WithoutGroupsMapping(authenticationRule.matching(User.Id("User1")))
              ))
            ),
            loggedUser = None,
            preferredGroup = None
          )
        }
        "one auth rule available is throwing an exception" in {
          assertNotMatchRule(
            settings = GroupsRule.Settings(
              groups = UniqueNonEmptyList.of(AlreadyResolved(groupFrom("g1").nel)),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                groupMappings = groups("g1"),
                mode = WithoutGroupsMapping(authenticationRule.throwing)
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
            settings = GroupsRule.Settings(
              groups = UniqueNonEmptyList.of(createMultiResolvableVariableFrom("group_@{user}")(AlwaysRightConvertible.from(Group.apply)).right.get),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                groupMappings = groups("group_user1"),
                mode = WithGroupsMapping(SingleRule(authRule.rejecting))
              ))
            ),
            loggedUser = None,
            preferredGroup = None
          )
        }
        "user cannot be authorized by authentication with authorization rule" in {
          assertNotMatchRule(
            settings = GroupsRule.Settings(
              groups = UniqueNonEmptyList.of(createMultiResolvableVariableFrom("group_@{user}")(AlwaysRightConvertible.from(Group.apply)).right.get),
              usersDefinitions = NonEmptyList.of(UserDef(
                usernames = userIdPatterns("user1"),
                groupMappings = groups("group_user1"),
                mode = WithGroupsMapping(SeparateRules(
                  authenticationRule.matching(User.Id("user1")),
                  authorizationRule.rejecting
                ))
              ))
            ),
            loggedUser = None,
            preferredGroup = None
          )
        }
      }
    }
  }

  private def assertMatchRule(settings: GroupsRule.Settings,
                              loggedUser: Option[User.Id],
                              preferredGroup: Option[Group],
                              caseSensitivity: Boolean = true)
                             (blockContextAssertion: BlockContext => Unit): Unit =
    assertRule(settings, loggedUser, preferredGroup, Some(blockContextAssertion), caseSensitivity)

  private def assertNotMatchRule(settings: GroupsRule.Settings,
                                 loggedUser: Option[User.Id],
                                 preferredGroup: Option[Group],
                                 caseSensitivity: Boolean = true): Unit =
    assertRule(settings, loggedUser, preferredGroup, blockContextAssertion = None, caseSensitivity)

  private def assertRule(settings: GroupsRule.Settings,
                         loggedUser: Option[User.Id],
                         preferredGroup: Option[Group],
                         blockContextAssertion: Option[BlockContext => Unit],
                         caseSensitivity: Boolean): Unit = {
    val rule = new GroupsRule(
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

  private def defaultOutputBlockContextAssertion(user: User.Id,
                                                 group: Group,
                                                 availableGroups: UniqueList[Group]): BlockContext => Unit =
    (blockContext: BlockContext) => {
      assertBlockContext(
        loggedUser = Some(DirectlyLoggedUser(user)),
        currentGroup = Some(group),
        availableGroups = availableGroups
      )(blockContext)
    }

  private def userIdPatterns(id: String, ids: String*) = {
    UserIdPatterns(
      UniqueNonEmptyList.unsafeFromList(
        (id :: ids.toList).map(str => UserIdPattern(NonEmptyString.unsafeFrom(str)))
      )
    )
  }

  private def groups(g1: String, gs: String*) = {
    UniqueNonEmptyList.of(noGroupMappingFrom(g1), gs.map(noGroupMappingFrom): _*)
  }
}

object GroupsRuleTests {

  private object authenticationRule {

    def matching(user: User.Id) = new AuthenticationRule {
      override val name: Rule.Name = Rule.Name("dummy-fulfilling")
      override protected val impersonators: List[ImpersonatorDef] = Nil
      override val caseMappingEquality: UserIdCaseMappingEquality = UserIdEq.caseSensitive
      override val eligibleUsers: EligibleUsersSupport = EligibleUsersSupport.NotAvailable

      override protected def exists(user: User.Id)
                                   (implicit userIdEq: Eq[User.Id]): Task[UserExistence] =
        Task.now(UserExistence.CannotCheck)

      override def tryToAuthenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] =
        Task.now(Fulfilled(blockContext.withUserMetadata(_.withLoggedUser(DirectlyLoggedUser(user)))))
    }

    val rejecting: AuthenticationRule = new AuthenticationRule {
      override val name: Rule.Name = Rule.Name("dummy-rejecting")

      override protected val impersonators: List[ImpersonatorDef] = Nil
      override val caseMappingEquality: UserIdCaseMappingEquality = UserIdEq.caseSensitive
      override val eligibleUsers: EligibleUsersSupport = EligibleUsersSupport.NotAvailable

      override def tryToAuthenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] =
        Task.now(Rejected())
      override def exists(user: User.Id)
                         (implicit userIdEq: Eq[User.Id]): Task[UserExistence] =
        Task.now(UserExistence.CannotCheck)
    }

    val throwing: AuthenticationRule = new AuthenticationRule {
      override val name: Rule.Name = Rule.Name("dummy-throwing")

      override protected val impersonators: List[ImpersonatorDef] = Nil
      override val eligibleUsers: EligibleUsersSupport = EligibleUsersSupport.NotAvailable
      override val caseMappingEquality: UserIdCaseMappingEquality = UserIdEq.caseSensitive

      override def tryToAuthenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] =
        Task.raiseError(new Exception("Sth went wrong"))
      override def exists(user: User.Id)
                         (implicit userIdEq: Eq[User.Id]): Task[UserExistence] = Task.now(UserExistence.CannotCheck)
    }
  }

  private object authorizationRule {

    def matching(groups: NonEmptyList[Group]): AuthorizationRule = new AuthorizationRule {
      override val name: Rule.Name = Rule.Name("dummy-fulfilling")

      override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] = {
        Task.now(Fulfilled(blockContext.withUserMetadata(
          _.withAvailableGroups(UniqueList.fromList(groups.toList))
        )))
      }
    }

    val rejecting: AuthorizationRule = new AuthorizationRule {
      override val name: Rule.Name = Rule.Name("dummy-rejecting")

      override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] =
        Task.now(Rejected())
    }
  }

  private object authRule {

    def matching(user: User.Id, groups: NonEmptyList[Group]): AuthRule = new AuthRule {
      override val name: Rule.Name = Rule.Name("dummy-fulfilling")

      override protected val impersonators: List[ImpersonatorDef] = List.empty
      override val eligibleUsers: EligibleUsersSupport = EligibleUsersSupport.NotAvailable
      override val caseMappingEquality: UserIdCaseMappingEquality = UserIdEq.caseSensitive

      override protected def exists(user: User.Id)(implicit userIdEq: Eq[User.Id]): Task[UserExistence] =
        Task.now(UserExistence.CannotCheck)

      override def tryToAuthenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] =
        Task.now(Fulfilled(blockContext.withUserMetadata(
          _.withLoggedUser(DirectlyLoggedUser(user))
            .withAvailableGroups(UniqueList.fromList(groups.toList))
        )))
    }

    val rejecting: AuthRule = new AuthRule {
      override val name: Rule.Name = Rule.Name("dummy-rejecting")

      override protected val impersonators: List[ImpersonatorDef] = List.empty
      override val eligibleUsers: EligibleUsersSupport = EligibleUsersSupport.NotAvailable
      override val caseMappingEquality: UserIdCaseMappingEquality = UserIdEq.caseSensitive

      override protected def exists(user: User.Id)(implicit userIdEq: Eq[User.Id]): Task[UserExistence] =
        Task.now(UserExistence.CannotCheck)

      override def tryToAuthenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Rule.RuleResult[B]] =
        Task.now(Rejected())
    }
  }
}
