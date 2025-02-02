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
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.*
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.mocks.NoOpMocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause.ImpersonationNotSupported
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.LdapAuthorizationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.LdapAuthorizationRule.Settings.{NegativeGroupsLogicSettings, PositiveGroupsLogicSettings}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.{Impersonation, ImpersonationSettings}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.LoggedUser.{DirectlyLoggedUser, ImpersonatedUser}
import tech.beshu.ror.accesscontrol.domain.User.Id
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.WithDummyRequestIdSupport
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

import scala.concurrent.duration.*
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

class LdapAuthorizationRuleTests
  extends AnyWordSpec
    with Inside
    with MockFactory
    with BlockContextAssertion
    with WithDummyRequestIdSupport {

  "An LdapAuthorizationRule" should {
    "match" when {
      "groups OR logic is used" when {
        "at least one allowed group matches the LDAP groups (1)" in {
          val ldapService = mockLdapService(
            name = "ldap1",
            groups = Map(User.Id("user1") -> Set(group("g1"), group("g2")))
          )
          assertMatchRule(
            settings = ldapAuthorizationRuleSettings(
              ldap = ldapService,
              groupsLogic = GroupsLogic.Or(GroupIds(
                UniqueNonEmptyList.of(GroupId("g3"), GroupId("g2"), GroupId("g1"))
              ))
            ),
            loggedUser = Some(DirectlyLoggedUser(Id("user1"))),
            preferredGroupId = None
          )(
            blockContextAssertion = defaultOutputBlockContextAssertion(
              user = Id("user1"),
              group = GroupId("g2"),
              availableGroups = UniqueList.of(group("g2"), group("g1"))
            )
          )
        }
        "at least one allowed group matches the LDAP groups (2)" in {
          val ldapService = mockLdapService(
            name = "ldap1",
            groups = Map(User.Id("user1") -> Set(group("g1"), group("g2")))
          )
          assertMatchRule(
            settings = ldapAuthorizationRuleSettings(
              ldap = ldapService,
              groupsLogic = GroupsLogic.Or(GroupIds(
                UniqueNonEmptyList.of(GroupIdLike.from("*3"), GroupIdLike.from("*2"))
              ))
            ),
            loggedUser = Some(DirectlyLoggedUser(Id("user1"))),
            preferredGroupId = None
          )(
            blockContextAssertion = defaultOutputBlockContextAssertion(
              user = Id("user1"),
              group = GroupId("g2"),
              availableGroups = UniqueList.of(group("g2"))
            )
          )
        }
      }
      "groups AND logic is used" when {
        "all allowed groups match the LDAP groups (1)" in {
          val ldapService = mockLdapService(
            name = "ldap1",
            groups = Map(User.Id("user1") -> Set(group("g1"), group("g2"), group("g3")))
          )
          assertMatchRule(
            settings = ldapAuthorizationRuleSettings(
              ldap = ldapService,
              groupsLogic = GroupsLogic.And(GroupIds(
                UniqueNonEmptyList.of(GroupId("g2"), GroupId("g1"))
              ))
            ),
            loggedUser = Some(DirectlyLoggedUser(Id("user1"))),
            preferredGroupId = None
          )(
            blockContextAssertion = defaultOutputBlockContextAssertion(
              user = Id("user1"),
              group = GroupId("g2"),
              availableGroups = UniqueList.of(group("g2"), group("g1"))
            )
          )
        }
        "all allowed groups match the LDAP groups (2)" in {
          val ldapService = mockLdapService(
            name = "ldap1",
            groups = Map(User.Id("user1") -> Set(group("g1"), group("g2"), group("g3")))
          )
          assertMatchRule(
            settings = ldapAuthorizationRuleSettings(
              ldap = ldapService,
              groupsLogic = GroupsLogic.And(GroupIds(
                UniqueNonEmptyList.of(GroupIdLike.from("*2"), GroupId("g1"))
              ))
            ),
            loggedUser = Some(DirectlyLoggedUser(Id("user1"))),
            preferredGroupId = None
          )(
            blockContextAssertion = defaultOutputBlockContextAssertion(
              user = Id("user1"),
              group = GroupId("g2"),
              availableGroups = UniqueList.of(group("g2"), group("g1"))
            )
          )
        }
      }
      "groups NOT_ANY_OF logic is used" when {
        "none of the forbidden groups match the LDAP groups" in {
          val ldapService = mockLdapService(
            name = "ldap1",
            groups = Map(User.Id("user1") -> Set(group("g1"), group("g2"), group("g3")))
          )
          assertMatchRule(
            settings = ldapAuthorizationRuleSettings(
              ldap = ldapService,
              groupsLogic = GroupsLogic.NotAnyOf(GroupIds(
                UniqueNonEmptyList.of(GroupId("g4"), GroupId("g5"))
              ))
            ),
            loggedUser = Some(DirectlyLoggedUser(Id("user1"))),
            preferredGroupId = Some(GroupId("g2"))
          )(
            blockContextAssertion = defaultOutputBlockContextAssertion(
              user = Id("user1"),
              group = GroupId("g2"),
              availableGroups = UniqueList.of(group("g1"), group("g2"), group("g3"))
            )
          )
        }
      }
      "groups NOT_ALL_OF logic is used" when {
        "none of the forbidden groups match the LDAP groups" in {
          val ldapService = mockLdapService(
            name = "ldap1",
            groups = Map(User.Id("user1") -> Set(group("g1"), group("g2"), group("g3")))
          )
          assertMatchRule(
            settings = ldapAuthorizationRuleSettings(
              ldap = ldapService,
              groupsLogic = GroupsLogic.NotAllOf(GroupIds(
                UniqueNonEmptyList.of(GroupId("g4"), GroupId("g5"))
              ))
            ),
            loggedUser = Some(DirectlyLoggedUser(Id("user1"))),
            preferredGroupId = Some(GroupId("g2"))
          )(
            blockContextAssertion = defaultOutputBlockContextAssertion(
              user = Id("user1"),
              group = GroupId("g2"),
              availableGroups = UniqueList.of(group("g1"), group("g2"), group("g3"))
            )
          )
        }
        "one of the forbidden groups match the LDAP groups" in {
          val ldapService = mockLdapService(
            name = "ldap1",
            groups = Map(User.Id("user1") -> Set(group("g1"), group("g2"), group("g3")))
          )
          assertMatchRule(
            settings = ldapAuthorizationRuleSettings(
              ldap = ldapService,
              groupsLogic = GroupsLogic.NotAllOf(GroupIds(
                UniqueNonEmptyList.of(GroupId("g2"), GroupId("g4"))
              ))
            ),
            loggedUser = Some(DirectlyLoggedUser(Id("user1"))),
            preferredGroupId = Some(GroupId("g1"))
          )(
            blockContextAssertion = defaultOutputBlockContextAssertion(
              user = Id("user1"),
              group = GroupId("g1"),
              availableGroups = UniqueList.of(group("g1"), group("g3"))
            )
          )
        }
      }
      "user is being impersonated" when {
        "impersonation is enabled" when {
          "mocks provider has a given user with allowed groups" in {
            val userGroupsInLdap1 = Map(User.Id("user1") -> Set(group("g1"), group("g2")))
            val ldapService = mockLdapService(name = "ldap1", groups = userGroupsInLdap1)

            assertMatchRule(
              settings = ldapAuthorizationRuleSettings(
                ldap = ldapService,
                groupsLogic = GroupsLogic.Or(GroupIds(
                  UniqueNonEmptyList.of(GroupId("g3"), GroupId("g2"), GroupId("g1"))
                ))
              ),
              impersonation = Impersonation.Enabled(ImpersonationSettings(
                impersonators = List.empty, // not needed in this context
                mocksProvider = mocksProviderForLdapFrom(Map(
                  LdapService.Name("ldap1") -> userGroupsInLdap1
                ))
              )),
              loggedUser = Some(ImpersonatedUser(Id("user1"), Id("admin"))),
              preferredGroupId = None
            )(
              blockContextAssertion = impersonatedUserOutputBlockContextAssertion(
                user = Id("user1"),
                group = GroupId("g2"),
                availableGroups = UniqueList.of(group("g2"), group("g1")),
                impersonator = Id("admin")
              )
            )
          }
        }
      }
    }
    "not match" when {
      "user is not logged" in {
        assertNotMatchRule(
          settings = ldapAuthorizationRuleSettings(
            ldap = mock[LdapAuthorizationService.WithoutGroupsFiltering],
            groupsLogic = GroupsLogic.Or(GroupIds(
              UniqueNonEmptyList.of(GroupId("g3"), GroupId("g2"), GroupId("g1"))
            ))
          ),
          loggedUser = None,
          preferredGroupId = None
        )
      }
      "user has no group which is permitted" in {
        val ldapService = mockLdapService(
          name = "ldap1",
          groups = Map(User.Id("user1") -> Set(group("g5")))
        )
        assertNotMatchRule(
          settings = ldapAuthorizationRuleSettings(
            ldap = ldapService,
            groupsLogic = GroupsLogic.Or(GroupIds(
              UniqueNonEmptyList.of(GroupId("g2"), GroupId("g1"))
            ))
          ),
          loggedUser = Some(DirectlyLoggedUser(Id("user1"))),
          preferredGroupId = None
        )
      }
      "user current group is not permitted" in {
        assertNotMatchRule(
          settings = ldapAuthorizationRuleSettings(
            ldap = mock[LdapAuthorizationService.WithoutGroupsFiltering],
            groupsLogic = GroupsLogic.Or(GroupIds(
              UniqueNonEmptyList.of(GroupId("g2"), GroupId("g1"))
            ))
          ),
          loggedUser = Some(DirectlyLoggedUser(Id("user1"))),
          preferredGroupId = Some(GroupId("g3"))
        )
      }
      "groups AND logic is used and not all configured groups are matched" in {
        val ldapService = mockLdapService(
          name = "ldap1",
          groups = Map(User.Id("user1") -> Set(group("g2"), group("g3")))
        )
        assertNotMatchRule(
          settings = ldapAuthorizationRuleSettings(
            ldap = ldapService,
            groupsLogic = GroupsLogic.And(GroupIds(
              UniqueNonEmptyList.of(GroupId("g2"), GroupIdLike.from("*1"))
            ))
          ),
          loggedUser = Some(DirectlyLoggedUser(Id("user1"))),
          preferredGroupId = None
        )
      }
      "groups NOT_ANY_OF logic is used" when {
        "not eligible preferred group present" in {
          val ldapService = mockLdapService(
            name = "ldap1",
            groups = Map(User.Id("user1") -> Set(group("g1"), group("g2"), group("g3")))
          )
          assertNotMatchRule(
            settings = ldapAuthorizationRuleSettings(
              ldap = ldapService,
              groupsLogic = GroupsLogic.NotAnyOf(GroupIds(
                UniqueNonEmptyList.of(GroupId("g4"), GroupId("g5"))
              ))
            ),
            loggedUser = Some(DirectlyLoggedUser(Id("user1"))),
            preferredGroupId = Some(GroupId("g4"))
          )
        }
        "one of the forbidden groups match the LDAP groups" in {
          val ldapService = mockLdapService(
            name = "ldap1",
            groups = Map(User.Id("user1") -> Set(group("g1"), group("g2"), group("g3")))
          )
          assertNotMatchRule(
            settings = ldapAuthorizationRuleSettings(
              ldap = ldapService,
              groupsLogic = GroupsLogic.NotAnyOf(GroupIds(
                UniqueNonEmptyList.of(GroupId("g2"), GroupId("g4"))
              ))
            ),
            loggedUser = Some(DirectlyLoggedUser(Id("user1"))),
            preferredGroupId = Some(GroupId("g1"))
          )
        }
        "all of the forbidden groups match the LDAP groups" in {
          val ldapService = mockLdapService(
            name = "ldap1",
            groups = Map(User.Id("user1") -> Set(group("g1"), group("g2"), group("g3")))
          )
          assertNotMatchRule(
            settings = ldapAuthorizationRuleSettings(
              ldap = ldapService,
              groupsLogic = GroupsLogic.NotAnyOf(GroupIds(
                UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"), GroupId("g3"))
              ))
            ),
            loggedUser = Some(DirectlyLoggedUser(Id("user1"))),
            preferredGroupId = Some(GroupId("g1"))
          )
        }
      }
      "groups NOT_ALL_OF logic is used" when {
        "not eligible preferred group present" in {
          val ldapService = mockLdapService(
            name = "ldap1",
            groups = Map(User.Id("user1") -> Set(group("g1"), group("g2"), group("g3")))
          )
          assertNotMatchRule(
            settings = ldapAuthorizationRuleSettings(
              ldap = ldapService,
              groupsLogic = GroupsLogic.NotAllOf(GroupIds(
                UniqueNonEmptyList.of(GroupId("g4"), GroupId("g5"))
              ))
            ),
            loggedUser = Some(DirectlyLoggedUser(Id("user1"))),
            preferredGroupId = Some(GroupId("g4"))
          )
        }
        "all of the forbidden groups match the LDAP groups" in {
          val ldapService = mockLdapService(
            name = "ldap1",
            groups = Map(User.Id("user1") -> Set(group("g1"), group("g2"), group("g3")))
          )
          assertNotMatchRule(
            settings = ldapAuthorizationRuleSettings(
              ldap = ldapService,
              groupsLogic = GroupsLogic.NotAllOf(GroupIds(
                UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"), GroupId("g3"))
              ))
            ),
            loggedUser = Some(DirectlyLoggedUser(Id("user1"))),
            preferredGroupId = Some(GroupId("g1"))
          )
        }
      }
      "LDAP service fails" in {
        assertRuleThrown(
          settings = ldapAuthorizationRuleSettings(
            ldap = mockFailedLdapService("service1", TestException("LDAP failed")),
            groupsLogic = GroupsLogic.Or(GroupIds(
              UniqueNonEmptyList.of(GroupId("g2"), GroupId("g1"))
            ))
          ),
          loggedUser = Some(DirectlyLoggedUser(Id("user1"))),
          preferredGroupId = None,
          exception = TestException("LDAP failed")
        )
      }
      "user is being impersonated" when {
        "impersonation is enabled" when {
          "mocks provider doesn't have a given user" in {
            val ldapService = mockLdapService(
              name = "ldap1",
              groups = Map(User.Id("user1") -> Set(group("g5")))
            )
            assertNotMatchRule(
              settings = ldapAuthorizationRuleSettings(
                ldap = ldapService,
                groupsLogic = GroupsLogic.Or(GroupIds(
                  UniqueNonEmptyList.of(GroupId("g3"), GroupId("g2"), GroupId("g1"))
                ))
              ),
              impersonation = Impersonation.Enabled(ImpersonationSettings(
                impersonators = List.empty, // not needed in this context
                mocksProvider = mocksProviderForLdapFrom(Map(
                  LdapService.Name("ldap1") -> Map(User.Id("user2") -> Set(group("g1"), group("g2")))
                ))
              )),
              loggedUser = Some(ImpersonatedUser(Id("user1"), Id("admin"))),
              preferredGroupId = None
            )
          }
          "mocks provider has a given user, but he doesn't have proper group" in {
            val ldapService = mockLdapService(
              name = "ldap1",
              groups = Map(User.Id("user1") -> Set(group("g5")))
            )
            assertNotMatchRule(
              settings = ldapAuthorizationRuleSettings(
                ldap = ldapService,
                groupsLogic = GroupsLogic.Or(GroupIds(
                  UniqueNonEmptyList.of(GroupId("g3"), GroupId("g2"), GroupId("g1"))
                ))
              ),
              impersonation = Impersonation.Enabled(ImpersonationSettings(
                impersonators = List.empty, // not needed in this context
                mocksProvider = mocksProviderForLdapFrom(Map(
                  LdapService.Name("ldap1") -> Map(User.Id("user1") -> Set(group("g5"), group("g6")))
                ))
              )),
              loggedUser = Some(ImpersonatedUser(Id("user1"), Id("admin"))),
              preferredGroupId = None
            )
          }
          "mocks provider is unavailable" in {
            val ldapService = mockLdapService(
              name = "ldap1",
              groups = Map(User.Id("user1") -> Set(group("g5")))
            )
            assertNotMatchRule(
              settings = ldapAuthorizationRuleSettings(
                ldap = ldapService,
                groupsLogic = GroupsLogic.Or(GroupIds(
                  UniqueNonEmptyList.of(GroupId("g3"), GroupId("g2"), GroupId("g1"))
                ))
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
              settings = ldapAuthorizationRuleSettings(
                ldap = mock[LdapAuthorizationService.WithoutGroupsFiltering],
                groupsLogic = GroupsLogic.Or(GroupIds(
                  UniqueNonEmptyList.of(GroupId("g3"), GroupId("g2"), GroupId("g1"))
                ))
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

  def ldapAuthorizationRuleSettings(ldap: LdapAuthorizationService,
                                    groupsLogic: GroupsLogic.PositiveGroupsLogic): PositiveGroupsLogicSettings =
    PositiveGroupsLogicSettings(ldap, groupsLogic)

  def ldapAuthorizationRuleSettings(ldap: LdapAuthorizationService.WithoutGroupsFiltering,
                                    groupsLogic: GroupsLogic.NegativeGroupsLogic): NegativeGroupsLogicSettings =
    NegativeGroupsLogicSettings(ldap, groupsLogic)

  private def assertMatchRule(settings: LdapAuthorizationRule.Settings,
                              impersonation: Impersonation = Impersonation.Disabled,
                              loggedUser: Option[LoggedUser],
                              preferredGroupId: Option[GroupId])
                             (blockContextAssertion: BlockContext => Unit): Unit =
    assertRule(settings, impersonation, loggedUser, preferredGroupId, AssertionType.RuleFulfilled(blockContextAssertion))

  private def assertNotMatchRule(settings: LdapAuthorizationRule.Settings,
                                 impersonation: Impersonation = Impersonation.Disabled,
                                 loggedUser: Option[LoggedUser],
                                 preferredGroupId: Option[GroupId],
                                 rejectionCause: Option[Cause] = None): Unit =
    assertRule(settings, impersonation, loggedUser, preferredGroupId, AssertionType.RuleRejected(rejectionCause))

  private def assertRuleThrown(settings: LdapAuthorizationRule.Settings,
                               impersonation: Impersonation = Impersonation.Disabled,
                               loggedUser: Option[LoggedUser],
                               preferredGroupId: Option[GroupId],
                               exception: Throwable): Unit =
    assertRule(settings, impersonation, loggedUser, preferredGroupId, AssertionType.RuleThrownException(exception))

  private def assertRule(settings: LdapAuthorizationRule.Settings,
                         impersonation: Impersonation,
                         loggedUser: Option[LoggedUser],
                         preferredGroupId: Option[GroupId],
                         assertionType: AssertionType): Unit = {
    val rule = new LdapAuthorizationRule(settings, CaseSensitivity.Enabled, impersonation)
    val requestContext = MockRequestContext.indices.copy(
      headers = preferredGroupId.map(_.toCurrentGroupHeader).toCovariantSet
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

  private def mockLdapService(name: NonEmptyString, groups: Map[User.Id, Set[Group]]) = {
    new LdapAuthorizationService.WithoutGroupsFiltering {
      override def id: LdapService.Name = LdapService.Name(name)

      override def groupsOf(id: User.Id)
                           (implicit requestId: RequestId): Task[UniqueList[Group]] = Task.delay {
        groups.get(id) match {
          case Some(g) => UniqueList.from(g)
          case None => UniqueList.empty
        }
      }

      override def ldapUsersService: LdapUsersService =
        throw new IllegalStateException("Should not be called")

      override def serviceTimeout: PositiveFiniteDuration = Refined.unsafeApply(1 second)
    }
  }

  private def mockFailedLdapService(name: NonEmptyString, failure: Throwable) = {
    new LdapAuthorizationService.WithoutGroupsFiltering {
      override def id: LdapService.Name = LdapService.Name(name)

      override def groupsOf(id: User.Id)
                           (implicit requestId: RequestId): Task[UniqueList[Group]] =
        Task.raiseError(failure)

      override def ldapUsersService: LdapUsersService =
        throw new IllegalStateException("Should not be called")

      override def serviceTimeout: PositiveFiniteDuration = Refined.unsafeApply(1 second)
    }
  }

  private def defaultOutputBlockContextAssertion(user: User.Id,
                                                 group: GroupId,
                                                 availableGroups: UniqueList[Group]): BlockContext => Unit =
    (blockContext: BlockContext) => {
      assertBlockContext(
        loggedUser = Some(DirectlyLoggedUser(user)),
        currentGroup = Some(group),
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

  private sealed case class TestException(message: String) extends Exception(message)
}
