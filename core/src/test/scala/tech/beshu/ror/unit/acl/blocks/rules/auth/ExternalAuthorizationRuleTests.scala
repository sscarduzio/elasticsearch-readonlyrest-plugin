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

import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.definitions.ExternalAuthorizationService
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.mocks.NoOpMocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause.ImpersonationNotSupported
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.ExternalAuthorizationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.{Impersonation, ImpersonationSettings}
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.LoggedUser.{DirectlyLoggedUser, ImpersonatedUser}
import tech.beshu.ror.accesscontrol.domain.User.Id
import tech.beshu.ror.accesscontrol.domain.{CaseSensitivity, Group, GroupIdLike, GroupsLogic, LoggedUser, PermittedGroupIds, User}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

class ExternalAuthorizationRuleTests
  extends AnyWordSpec with MockFactory with Inside with BlockContextAssertion {

  "An ExternalAuthorizationRule" should {
    "match" when {
      "user is logged and match configured used list" when {
        "has current group" when {
          "groups OR logic is used" when {
            "at least one allowed group matches the external groups (1)" in {
              val service = mockExternalAuthorizationService(
                name = "service1",
                groups = Map(User.Id("user2") -> Set(groupFromId("g2"), groupFromId("g3")))
              )

              assertMatchRule(
                settings = ExternalAuthorizationRule.Settings(
                  service = service,
                  permittedGroupsLogic = GroupsLogic.Or(PermittedGroupIds(
                    UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
                  )),
                  users = UniqueNonEmptyList.of(User.Id("user1"), User.Id("user2"))
                ),
                loggedUser = Some(DirectlyLoggedUser(User.Id("user2"))),
                preferredGroupId = Some(GroupId("g2"))
              )(
                blockContextAssertion = defaultOutputBlockContextAssertion(
                  user = User.Id("user2"),
                  preferredGroupId = GroupId("g2"),
                  availableGroups = UniqueList.of(groupFromId("g2"))
                )
              )
            }
            "at least one allowed group matches the external groups (2)" in {
              val service = mockExternalAuthorizationService(
                name = "service1",
                groups = Map(User.Id("user2") -> Set(groupFromId("g2"), groupFromId("g3")))
              )

              assertMatchRule(
                settings = ExternalAuthorizationRule.Settings(
                  service = service,
                  permittedGroupsLogic = GroupsLogic.Or(PermittedGroupIds(
                    UniqueNonEmptyList.of(GroupId("g1"), GroupIdLike.from("*2"))
                  )),
                  users = UniqueNonEmptyList.of(User.Id("user1"), User.Id("user2"))
                ),
                loggedUser = Some(DirectlyLoggedUser(User.Id("user2"))),
                preferredGroupId = Some(GroupId("g2"))
              )(
                blockContextAssertion = defaultOutputBlockContextAssertion(
                  user = User.Id("user2"),
                  preferredGroupId = GroupId("g2"),
                  availableGroups = UniqueList.of(groupFromId("g2"))
                )
              )
            }
          }
          "groups AND logic is used" when {
            "all allowed groups match the external groups (1)" in {
              val service = mockExternalAuthorizationService(
                name = "service1",
                groups = Map(User.Id("user2") -> Set(groupFromId("g1"), groupFromId("g2"), groupFromId("g3")))
              )

              assertMatchRule(
                settings = ExternalAuthorizationRule.Settings(
                  service = service,
                  permittedGroupsLogic = GroupsLogic.And(PermittedGroupIds(
                    UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
                  )),
                  users = UniqueNonEmptyList.of(User.Id("user1"), User.Id("user2"))
                ),
                loggedUser = Some(DirectlyLoggedUser(User.Id("user2"))),
                preferredGroupId = Some(GroupId("g2"))
              )(
                blockContextAssertion = defaultOutputBlockContextAssertion(
                  user = User.Id("user2"),
                  preferredGroupId = GroupId("g2"),
                  availableGroups = UniqueList.of(groupFromId("g1"), groupFromId("g2"))
                )
              )
            }
            "all allowed groups match the external groups (2)" in {
              val service = mockExternalAuthorizationService(
                name = "service1",
                groups = Map(User.Id("user2") -> Set(groupFromId("g1"), groupFromId("g2"), groupFromId("g3")))
              )

              assertMatchRule(
                settings = ExternalAuthorizationRule.Settings(
                  service = service,
                  permittedGroupsLogic = GroupsLogic.And(PermittedGroupIds(
                    UniqueNonEmptyList.of(GroupId("g1"), GroupIdLike.from("*2"))
                  )),
                  users = UniqueNonEmptyList.of(User.Id("user1"), User.Id("user2"))
                ),
                loggedUser = Some(DirectlyLoggedUser(User.Id("user2"))),
                preferredGroupId = Some(GroupId("g2"))
              )(
                blockContextAssertion = defaultOutputBlockContextAssertion(
                  user = User.Id("user2"),
                  preferredGroupId = GroupId("g2"),
                  availableGroups = UniqueList.of(groupFromId("g1"), groupFromId("g2"))
                )
              )
            }
          }
        }
        "doesn't have current group set, but there is non empty intersection set between fetched groups and configured ones" in {
          val service = mockExternalAuthorizationService(
            name = "service1",
            groups = Map(User.Id("user2") -> Set(groupFromId("g1"), groupFromId("g2"), groupFromId("g3")))
          )

          assertMatchRule(
            settings = ExternalAuthorizationRule.Settings(
              service = service,
              permittedGroupsLogic = GroupsLogic.Or(PermittedGroupIds(
                UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
              )),
              users = UniqueNonEmptyList.of(User.Id("user1"), User.Id("user2"))
            ),
            loggedUser = Some(DirectlyLoggedUser(User.Id("user2"))),
            preferredGroupId = None
          )(
            blockContextAssertion = defaultOutputBlockContextAssertion(
              user = User.Id("user2"),
              preferredGroupId = GroupId("g1"),
              availableGroups = UniqueList.of(groupFromId("g1"), groupFromId("g2"))
            )
          )
        }
        "configured user name has wildcard" in {
          val service = mockExternalAuthorizationService(
            name = "service1",
            groups = Map(User.Id("user2") -> Set(groupFromId("g1"), groupFromId("g2"), groupFromId("g3")))
          )

          assertMatchRule(
            settings = ExternalAuthorizationRule.Settings(
              service = service,
              permittedGroupsLogic = GroupsLogic.Or(PermittedGroupIds(
                UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
              )),
              users = UniqueNonEmptyList.of(User.Id("*"))
            ),
            loggedUser = Some(DirectlyLoggedUser(User.Id("user2"))),
            preferredGroupId = None
          )(
            blockContextAssertion = defaultOutputBlockContextAssertion(
              user = User.Id("user2"),
              preferredGroupId = GroupId("g1"),
              availableGroups = UniqueList.of(groupFromId("g1"), groupFromId("g2"))
            )
          )
        }
      }
      "user is being impersonated" when {
        "impersonation is enabled" when {
          "mocks provider has a given user with proper groups for the given external service" in {
            val user2GroupsInService1 = Map(User.Id("user2") -> Set(groupFromId("g2"), groupFromId("g3")))
            val service = mockExternalAuthorizationService(name = "service1", groups = user2GroupsInService1)

            assertMatchRule(
              settings = ExternalAuthorizationRule.Settings(
                service = service,
                permittedGroupsLogic = GroupsLogic.Or(PermittedGroupIds(
                  UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
                )),
                users = UniqueNonEmptyList.of(User.Id("user1"), User.Id("user2"))
              ),
              impersonation = Impersonation.Enabled(ImpersonationSettings(
                impersonators = List.empty, // not needed in this context
                mocksProvider = mocksProviderForExternalAuthzServiceFrom(Map(
                  ExternalAuthorizationService.Name("service1") -> user2GroupsInService1
                ))
              )),
              loggedUser = Some(ImpersonatedUser(User.Id("user2"), User.Id("admin"))),
              preferredGroupId = Some(GroupId("g2"))
            )(
              blockContextAssertion = impersonatedUserOutputBlockContextAssertion(
                user = User.Id("user2"),
                group = GroupId("g2"),
                availableGroups = UniqueList.of(groupFromId("g2")),
                impersonator = User.Id("admin")
              )
            )
          }
        }
      }
    }
    "not match" when {
      "user is not logged in" in {
        assertNotMatchRule(
          settings = ExternalAuthorizationRule.Settings(
            service = mock[ExternalAuthorizationService],
            permittedGroupsLogic = GroupsLogic.Or(PermittedGroupIds(
              UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
            )),
            users = UniqueNonEmptyList.of(User.Id("user1"))
          ),
          loggedUser = None,
          preferredGroupId = None
        )
      }
      "user is logged, but his id is not listed on user config list" in {
        assertNotMatchRule(
          settings = ExternalAuthorizationRule.Settings(
            service = mock[ExternalAuthorizationService],
            permittedGroupsLogic = GroupsLogic.Or(PermittedGroupIds(
              UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
            )),
            users = UniqueNonEmptyList.of(User.Id("user1"))
          ),
          loggedUser = Some(DirectlyLoggedUser(User.Id("user2"))),
          preferredGroupId = None
        )
      }
      "authorization service returns empty groups list" in {
        val service = mockExternalAuthorizationService(
          name = "service1",
          groups = Map(User.Id("user2") -> Set.empty)
        )

        assertNotMatchRule(
          settings = ExternalAuthorizationRule.Settings(
            service = service,
            permittedGroupsLogic = GroupsLogic.Or(PermittedGroupIds(
              UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
            )),
            users = UniqueNonEmptyList.of(User.Id("*"))
          ),
          loggedUser = Some(DirectlyLoggedUser(User.Id("user2"))),
          preferredGroupId = None
        )
      }
      "authorization service groups for given user has empty intersection with configured groups" in {
        val service = mockExternalAuthorizationService(
          name = "service1",
          groups = Map(User.Id("user2") -> Set(groupFromId("g3"), groupFromId("g4")))
        )

        assertNotMatchRule(
          settings = ExternalAuthorizationRule.Settings(
            service = service,
            permittedGroupsLogic = GroupsLogic.Or(PermittedGroupIds(
              UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
            )),
            users = UniqueNonEmptyList.of(User.Id("*"))
          ),
          loggedUser = Some(DirectlyLoggedUser(User.Id("user2"))),
          preferredGroupId = None
        )
      }
      "groups AND logic is used and not all configured groups are matched" in {
        val service = mockExternalAuthorizationService(
          name = "service1",
          groups = Map(User.Id("user2") -> Set(groupFromId("g3"), groupFromId("g4")))
        )

        assertNotMatchRule(
          settings = ExternalAuthorizationRule.Settings(
            service = service,
            permittedGroupsLogic = GroupsLogic.And(PermittedGroupIds(
              UniqueNonEmptyList.of(GroupId("g1"), GroupIdLike.from("*2"))
            )),
            users = UniqueNonEmptyList.of(User.Id("*"))
          ),
          loggedUser = Some(DirectlyLoggedUser(User.Id("user2"))),
          preferredGroupId = None
        )
      }
      "current group is set for a given user but it's not present in intersection groups set" in {
        assertNotMatchRule(
          settings = ExternalAuthorizationRule.Settings(
            service = mock[ExternalAuthorizationService],
            permittedGroupsLogic = GroupsLogic.Or(PermittedGroupIds(
              UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
            )),
            users = UniqueNonEmptyList.of(User.Id("*"))
          ),
          loggedUser = Some(DirectlyLoggedUser(User.Id("user2"))),
          preferredGroupId = Some(GroupId("g3"))
        )
      }
      "user is being impersonated" when {
        "impersonation is enabled" when {
          "mocks provider doesn't have a given user" in {
            val user2GroupsInService1 = Map(User.Id("user2") -> Set(groupFromId("g1"), groupFromId("g2")))
            val service = mockExternalAuthorizationService(name = "service1", groups = user2GroupsInService1)
            assertNotMatchRule(
              settings = ExternalAuthorizationRule.Settings(
                service = service,
                permittedGroupsLogic = GroupsLogic.Or(PermittedGroupIds(
                  UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
                )),
                users = UniqueNonEmptyList.of(User.Id("user1"), User.Id("user2"))
              ),
              impersonation = Impersonation.Enabled(ImpersonationSettings(
                impersonators = List.empty, // not needed in this context
                mocksProvider = mocksProviderForExternalAuthzServiceFrom(Map(
                  ExternalAuthorizationService.Name("service1") -> user2GroupsInService1
                ))
              )),
              loggedUser = Some(ImpersonatedUser(Id("user1"), Id("admin"))),
              preferredGroupId = None
            )
          }
          "mocks provider has a given user, but he doesn't have proper group" in {
            val user2GroupsInService1 = Map(User.Id("user1") -> Set(groupFromId("g5"), groupFromId("g6")))
            val service = mockExternalAuthorizationService(name = "service1", groups = user2GroupsInService1)

            assertNotMatchRule(
              settings = ExternalAuthorizationRule.Settings(
                service = service,
                permittedGroupsLogic = GroupsLogic.Or(PermittedGroupIds(
                  UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
                )),
                users = UniqueNonEmptyList.of(User.Id("user1"), User.Id("user2"))
              ),
              impersonation = Impersonation.Enabled(ImpersonationSettings(
                impersonators = List.empty, // not needed in this context
                mocksProvider = mocksProviderForExternalAuthzServiceFrom(Map(
                  ExternalAuthorizationService.Name("service1") -> user2GroupsInService1
                ))
              )),
              loggedUser = Some(ImpersonatedUser(Id("user1"), Id("admin"))),
              preferredGroupId = None
            )
          }
          "mocks provider is unavailable" in {
            val user2GroupsInService1 = Map(User.Id("user1") -> Set(groupFromId("g5"), groupFromId("g6")))
            val service = mockExternalAuthorizationService(name = "service1", groups = user2GroupsInService1)

            assertNotMatchRule(
              settings = ExternalAuthorizationRule.Settings(
                service = service,
                permittedGroupsLogic = GroupsLogic.Or(PermittedGroupIds(
                  UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
                )),
                users = UniqueNonEmptyList.of(User.Id("user1"), User.Id("user2"))
              ),
              impersonation = Impersonation.Enabled(ImpersonationSettings(
                impersonators = List.empty, // not needed in this context
                mocksProvider = NoOpMocksProvider
              )),
              loggedUser = Some(ImpersonatedUser(Id("user1"), Id("admin"))),
              preferredGroupId = None,
              rejectionCause = Some(ImpersonationNotSupported)
            )
          }
        }
        "impersonation is disabled" when {
          "admin is trying to impersonate user" in {
            assertNotMatchRule(
              settings = ExternalAuthorizationRule.Settings(
                service = mock[ExternalAuthorizationService],
                permittedGroupsLogic = GroupsLogic.Or(PermittedGroupIds(
                  UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
                )),
                users = UniqueNonEmptyList.of(User.Id("user1"), User.Id("user2"))
              ),
              impersonation = Impersonation.Disabled,
              loggedUser = Some(ImpersonatedUser(Id("user1"), Id("admin"))),
              preferredGroupId = None,
              rejectionCause = Some(ImpersonationNotSupported)
            )
          }
        }
      }
    }
  }

  private def assertMatchRule(settings: ExternalAuthorizationRule.Settings,
                              impersonation: Impersonation = Impersonation.Disabled,
                              loggedUser: Option[LoggedUser],
                              preferredGroupId: Option[GroupId])
                             (blockContextAssertion: BlockContext => Unit): Unit =
    assertRule(settings, impersonation, loggedUser, preferredGroupId, AssertionType.RuleFulfilled(blockContextAssertion))

  private def assertNotMatchRule(settings: ExternalAuthorizationRule.Settings,
                                 impersonation: Impersonation = Impersonation.Disabled,
                                 loggedUser: Option[LoggedUser],
                                 preferredGroupId: Option[GroupId],
                                 rejectionCause: Option[Cause] = None): Unit =
    assertRule(settings, impersonation, loggedUser, preferredGroupId, AssertionType.RuleRejected(rejectionCause))

  private def assertRule(settings: ExternalAuthorizationRule.Settings,
                         impersonation: Impersonation,
                         loggedUser: Option[LoggedUser],
                         preferredGroup: Option[GroupId],
                         assertionType: AssertionType): Unit = {
    val rule = new ExternalAuthorizationRule(settings, CaseSensitivity.Enabled, impersonation)
    val requestContext = MockRequestContext.indices.copy(
      headers = preferredGroup.map(_.toCurrentGroupHeader).toSet
    )
    val blockContext = GeneralIndexRequestBlockContext(
      requestContext = requestContext,
      userMetadata = loggedUser match {
        case Some(user) => UserMetadata.from(requestContext).withLoggedUser(user)
        case None => UserMetadata.from(requestContext)
      },
      responseHeaders = Set.empty,
      responseTransformations = List.empty,
      filteredIndices = Set.empty,
      allAllowedIndices = Set.empty
    )
    val result = Try(rule.check(blockContext).runSyncUnsafe(1 second))
    assertionType match {
      case AssertionType.RuleFulfilled(blockContextAssertion) =>
        inside(result) { case Success(Fulfilled(outBlockContext)) =>
          blockContextAssertion(outBlockContext)
        }
      case AssertionType.RuleRejected(cause) =>
        result should be(Success(Rejected(cause)))
      case AssertionType.RuleThrownException(ex) =>
        result should be(Failure(ex))
    }
  }

  private def defaultOutputBlockContextAssertion(user: User.Id,
                                                 preferredGroupId: GroupId,
                                                 availableGroups: UniqueList[Group]): BlockContext => Unit =
    (blockContext: BlockContext) => {
      assertBlockContext(
        loggedUser = Some(DirectlyLoggedUser(user)),
        currentGroup = Some(preferredGroupId),
        availableGroups = availableGroups
      )(blockContext)
    }

  private def impersonatedUserOutputBlockContextAssertion(user: User.Id,
                                                          group: GroupId,
                                                          availableGroups: UniqueList[Group],
                                                          impersonator: User.Id): BlockContext => Unit =
    (blockContext: BlockContext) => {
      assertBlockContext(
        loggedUser = Some(ImpersonatedUser(user, impersonator)),
        currentGroup = Some(group),
        availableGroups = availableGroups
      )(blockContext)
    }

  private def mockExternalAuthorizationService(name: NonEmptyString, groups: Map[User.Id, Set[Group]]) =
    new ExternalAuthorizationService {
      override def id: ExternalAuthorizationService.Name = ExternalAuthorizationService.Name(name)

      override def grantsFor(userId: User.Id): Task[UniqueList[Group]] = Task.delay {
        groups.get(userId) match {
          case Some(g) => UniqueList.fromIterable(g)
          case None => UniqueList.empty
        }
      }

      override def serviceTimeout: Refined[FiniteDuration, Positive] = Refined.unsafeApply(5 second)
    }
}
