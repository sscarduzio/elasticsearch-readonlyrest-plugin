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
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.{LdapAuthorizationService, LdapService, LdapUser}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.mocks.NoOpMocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.{ExternalAuthorizationRule, LdapAuthorizationRule}
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleResult.Rejected.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleResult.Rejected.Cause.ImpersonationNotSupported
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation.{Impersonation, ImpersonationSettings}
import tech.beshu.ror.accesscontrol.domain.GroupLike.GroupName
import tech.beshu.ror.accesscontrol.domain.LoggedUser.{DirectlyLoggedUser, ImpersonatedUser}
import tech.beshu.ror.accesscontrol.domain.User.Id
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.UserIdEq
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

class LdapAuthorizationRuleTests
  extends AnyWordSpec
    with Inside
    with MockFactory
    with BlockContextAssertion {

  "An LdapAuthorizationRule" should {
    "match" when {
      "groups OR logic is used" when {
        "at least one allowed group matches the LDAP groups (1)" in {
          val ldapService = mockLdapService(
            name = "ldap1",
            groups = Map(User.Id("user1") -> Set(GroupName("g1"), GroupName("g2")))
          )
          assertMatchRule(
            settings = LdapAuthorizationRule.Settings(
              ldap = ldapService,
              permittedGroupsLogic = GroupsLogic.Or(PermittedGroups(
                UniqueNonEmptyList.of(GroupName("g3"), GroupName("g2"), GroupName("g1"))
              ))
            ),
            loggedUser = Some(DirectlyLoggedUser(Id("user1"))),
            preferredGroup = None
          )(
            blockContextAssertion = defaultOutputBlockContextAssertion(
              user = Id("user1"),
              group = GroupName("g2"),
              availableGroups = UniqueList.of(GroupName("g2"), GroupName("g1"))
            )
          )
        }
        "at least one allowed group matches the LDAP groups (2)" in {
          val ldapService = mockLdapService(
            name = "ldap1",
            groups = Map(User.Id("user1") -> Set(GroupName("g1"), GroupName("g2")))
          )
          assertMatchRule(
            settings = LdapAuthorizationRule.Settings(
              ldap = ldapService,
              permittedGroupsLogic = GroupsLogic.Or(PermittedGroups(
                UniqueNonEmptyList.of(GroupLike.from("*3"), GroupLike.from("*2"))
              ))
            ),
            loggedUser = Some(DirectlyLoggedUser(Id("user1"))),
            preferredGroup = None
          )(
            blockContextAssertion = defaultOutputBlockContextAssertion(
              user = Id("user1"),
              group = GroupName("g2"),
              availableGroups = UniqueList.of(GroupName("g2"))
            )
          )
        }
      }
      "groups AND logic is used" when {
        "all allowed groups match the LDAP groups (1)" in {
          val ldapService = mockLdapService(
            name = "ldap1",
            groups = Map(User.Id("user1") -> Set(GroupName("g1"), GroupName("g2"), GroupName("g3")))
          )
          assertMatchRule(
            settings = LdapAuthorizationRule.Settings(
              ldap = ldapService,
              permittedGroupsLogic = GroupsLogic.And(PermittedGroups(
                UniqueNonEmptyList.of(GroupName("g2"), GroupName("g1"))
              ))
            ),
            loggedUser = Some(DirectlyLoggedUser(Id("user1"))),
            preferredGroup = None
          )(
            blockContextAssertion = defaultOutputBlockContextAssertion(
              user = Id("user1"),
              group = GroupName("g2"),
              availableGroups = UniqueList.of(GroupName("g2"), GroupName("g1"))
            )
          )
        }
        "all allowed groups match the LDAP groups (2)" in {
          val ldapService = mockLdapService(
            name = "ldap1",
            groups = Map(User.Id("user1") -> Set(GroupName("g1"), GroupName("g2"), GroupName("g3")))
          )
          assertMatchRule(
            settings = LdapAuthorizationRule.Settings(
              ldap = ldapService,
              permittedGroupsLogic = GroupsLogic.And(PermittedGroups(
                UniqueNonEmptyList.of(GroupLike.from("*2"), GroupName("g1"))
              ))
            ),
            loggedUser = Some(DirectlyLoggedUser(Id("user1"))),
            preferredGroup = None
          )(
            blockContextAssertion = defaultOutputBlockContextAssertion(
              user = Id("user1"),
              group = GroupName("g2"),
              availableGroups = UniqueList.of(GroupName("g2"), GroupName("g1"))
            )
          )
        }
      }
      "user is being impersonated" when {
        "impersonation is enabled" when {
          "mocks provider has a given user with allowed groups" in {
            val userGroupsInLdap1 = Map(User.Id("user1") -> Set(GroupName("g1"), GroupName("g2")))
            val ldapService = mockLdapService(name = "ldap1", groups = userGroupsInLdap1)

            assertMatchRule(
              settings = LdapAuthorizationRule.Settings(
                ldap = ldapService,
                permittedGroupsLogic = GroupsLogic.Or(PermittedGroups(
                  UniqueNonEmptyList.of(GroupName("g3"), GroupName("g2"), GroupName("g1"))
                ))
              ),
              impersonation = Impersonation.Enabled(ImpersonationSettings(
                impersonators = List.empty, // not needed in this context
                mocksProvider = mocksProviderForLdapFrom(Map(
                  LdapService.Name("ldap1") -> userGroupsInLdap1
                ))
              )),
              loggedUser = Some(ImpersonatedUser(Id("user1"), Id("admin"))),
              preferredGroup = None
            )(
              blockContextAssertion = impersonatedUserOutputBlockContextAssertion(
                user = Id("user1"),
                group = GroupName("g2"),
                availableGroups = UniqueList.of(GroupName("g2"), GroupName("g1")),
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
          settings = LdapAuthorizationRule.Settings(
            ldap = mock[LdapAuthorizationService],
            permittedGroupsLogic = GroupsLogic.Or(PermittedGroups(
              UniqueNonEmptyList.of(GroupName("g3"), GroupName("g2"), GroupName("g1"))
            ))
          ),
          loggedUser = None,
          preferredGroup = None
        )
      }
      "user has no group which is permitted" in {
        val ldapService = mockLdapService(
          name = "ldap1",
          groups = Map(User.Id("user1") -> Set(GroupName("g5")))
        )
        assertNotMatchRule(
          settings = LdapAuthorizationRule.Settings(
            ldap = ldapService,
            permittedGroupsLogic = GroupsLogic.Or(PermittedGroups(
              UniqueNonEmptyList.of(GroupName("g2"), GroupName("g1"))
            ))
          ),
          loggedUser = Some(DirectlyLoggedUser(Id("user1"))),
          preferredGroup = None
        )
      }
      "user current group is not permitted" in {
        assertNotMatchRule(
          settings = LdapAuthorizationRule.Settings(
            ldap = mock[LdapAuthorizationService],
            permittedGroupsLogic = GroupsLogic.Or(PermittedGroups(
              UniqueNonEmptyList.of(GroupName("g2"), GroupName("g1"))
            ))
          ),
          loggedUser = Some(DirectlyLoggedUser(Id("user1"))),
          preferredGroup = Some(GroupName("g3"))
        )
      }
      "groups AND logic is used and not all configured groups are matched" in {
        val ldapService = mockLdapService(
          name = "ldap1",
          groups = Map(User.Id("user1") -> Set(GroupName("g2"), GroupName("g3")))
        )
        assertNotMatchRule(
          settings = LdapAuthorizationRule.Settings(
            ldap = ldapService,
            permittedGroupsLogic = GroupsLogic.And(PermittedGroups(
              UniqueNonEmptyList.of(GroupName("g2"), GroupLike.from("*1"))
            ))
          ),
          loggedUser = Some(DirectlyLoggedUser(Id("user1"))),
          preferredGroup = None
        )
      }
      "LDAP service fails" in {
        assertRuleThrown(
          settings = LdapAuthorizationRule.Settings(
            ldap = mockFailedLdapService("service1", TestException("LDAP failed")),
            permittedGroupsLogic = GroupsLogic.Or(PermittedGroups(
              UniqueNonEmptyList.of(GroupName("g2"), GroupName("g1"))
            ))
          ),
          loggedUser = Some(DirectlyLoggedUser(Id("user1"))),
          preferredGroup = None,
          exception = TestException("LDAP failed")
        )
      }
      "user is being impersonated" when {
        "impersonation is enabled" when {
          "mocks provider doesn't have a given user" in {
            val ldapService = mockLdapService(
              name = "ldap1",
              groups = Map(User.Id("user1") -> Set(GroupName("g5")))
            )
            assertNotMatchRule(
              settings = LdapAuthorizationRule.Settings(
                ldap = ldapService,
                permittedGroupsLogic = GroupsLogic.Or(PermittedGroups(
                  UniqueNonEmptyList.of(GroupName("g3"), GroupName("g2"), GroupName("g1"))
                ))
              ),
              impersonation = Impersonation.Enabled(ImpersonationSettings(
                impersonators = List.empty, // not needed in this context
                mocksProvider = mocksProviderForLdapFrom(Map(
                  LdapService.Name("ldap1") -> Map(User.Id("user2") -> Set(GroupName("g1"), GroupName("g2")))
                ))
              )),
              loggedUser = Some(ImpersonatedUser(Id("user1"), Id("admin"))),
              preferredGroup = None
            )
          }
          "mocks provider has a given user, but he doesn't have proper group" in {
            val ldapService = mockLdapService(
              name = "ldap1",
              groups = Map(User.Id("user1") -> Set(GroupName("g5")))
            )
            assertNotMatchRule(
              settings = LdapAuthorizationRule.Settings(
                ldap = ldapService,
                permittedGroupsLogic = GroupsLogic.Or(PermittedGroups(
                  UniqueNonEmptyList.of(GroupName("g3"), GroupName("g2"), GroupName("g1"))
                ))
              ),
              impersonation = Impersonation.Enabled(ImpersonationSettings(
                impersonators = List.empty, // not needed in this context
                mocksProvider = mocksProviderForLdapFrom(Map(
                  LdapService.Name("ldap1") -> Map(User.Id("user1") -> Set(GroupName("g5"), GroupName("g6")))
                ))
              )),
              loggedUser = Some(ImpersonatedUser(Id("user1"), Id("admin"))),
              preferredGroup = None
            )
          }
          "mocks provider is unavailable" in {
            val ldapService = mockLdapService(
              name = "ldap1",
              groups = Map(User.Id("user1") -> Set(GroupName("g5")))
            )
            assertNotMatchRule(
              settings = LdapAuthorizationRule.Settings(
                ldap = ldapService,
                permittedGroupsLogic = GroupsLogic.Or(PermittedGroups(
                  UniqueNonEmptyList.of(GroupName("g3"), GroupName("g2"), GroupName("g1"))
                ))
              ),
              impersonation = Impersonation.Enabled(ImpersonationSettings(
                impersonators = List.empty, // not needed in this context
                mocksProvider = NoOpMocksProvider
              )),
              loggedUser = Some(ImpersonatedUser(Id("user1"), Id("admin"))),
              preferredGroup = None,
              rejectionCause = Some(ImpersonationNotSupported)
            )
          }
        }
        "impersonation is disabled" when {
          "admin is trying to impersonate user" in {
            assertNotMatchRule(
              settings = LdapAuthorizationRule.Settings(
                ldap = mock[LdapAuthorizationService],
                permittedGroupsLogic = GroupsLogic.Or(PermittedGroups(
                  UniqueNonEmptyList.of(GroupName("g3"), GroupName("g2"), GroupName("g1"))
                ))
              ),
              impersonation = Impersonation.Disabled,
              loggedUser = Some(ImpersonatedUser(Id("user1"), Id("admin"))),
              preferredGroup = None,
              rejectionCause = Some(ImpersonationNotSupported)
            )
          }
        }
      }
    }
  }

  private def assertMatchRule(settings: LdapAuthorizationRule.Settings,
                              impersonation: Impersonation = Impersonation.Disabled,
                              loggedUser: Option[LoggedUser],
                              preferredGroup: Option[GroupName])
                             (blockContextAssertion: BlockContext => Unit): Unit =
    assertRule(settings, impersonation, loggedUser, preferredGroup, AssertionType.RuleFulfilled(blockContextAssertion))

  private def assertNotMatchRule(settings: LdapAuthorizationRule.Settings,
                                 impersonation: Impersonation = Impersonation.Disabled,
                                 loggedUser: Option[LoggedUser],
                                 preferredGroup: Option[GroupName],
                                 rejectionCause: Option[Cause] = None): Unit =
    assertRule(settings, impersonation, loggedUser, preferredGroup, AssertionType.RuleRejected(rejectionCause))

  private def assertRuleThrown(settings: LdapAuthorizationRule.Settings,
                               impersonation: Impersonation = Impersonation.Disabled,
                               loggedUser: Option[LoggedUser],
                               preferredGroup: Option[GroupName],
                               exception: Throwable): Unit =
    assertRule(settings, impersonation, loggedUser, preferredGroup, AssertionType.RuleThrownException(exception))

  private def assertRule(settings: LdapAuthorizationRule.Settings,
                         impersonation: Impersonation,
                         loggedUser: Option[LoggedUser],
                         preferredGroup: Option[GroupName],
                         assertionType: AssertionType): Unit = {
    val rule = new LdapAuthorizationRule(settings, impersonation, UserIdEq.caseSensitive)
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

  private def mockLdapService(name: NonEmptyString, groups: Map[User.Id, Set[GroupName]]) = {
    new LdapAuthorizationService {
      override def id: LdapService.Name = LdapService.Name(name)

      override def groupsOf(id: User.Id): Task[UniqueList[GroupName]] = Task.delay {
        groups.get(id) match {
          case Some(g) => UniqueList.fromTraversable(g)
          case None => UniqueList.empty
        }
      }

      override def ldapUserBy(userId: User.Id): Task[Option[LdapUser]] =
        Task.raiseError(new IllegalStateException("Should not be called"))

      override def serviceTimeout: Refined[FiniteDuration, Positive] = Refined.unsafeApply(1 second)
    }
  }

  private def mockFailedLdapService(name: NonEmptyString, failure: Throwable) = {
    new LdapAuthorizationService {
      override def id: LdapService.Name = LdapService.Name(name)

      override def groupsOf(id: User.Id): Task[UniqueList[GroupName]] =
        Task.raiseError(failure)

      override def ldapUserBy(userId: User.Id): Task[Option[LdapUser]] =
        Task.raiseError(new IllegalStateException("Should not be called"))

      override def serviceTimeout: Refined[FiniteDuration, Positive] = Refined.unsafeApply(1 second)
    }
  }

  private def defaultOutputBlockContextAssertion(user: User.Id,
                                                 group: GroupName,
                                                 availableGroups: UniqueList[GroupName]): BlockContext => Unit =
    (blockContext: BlockContext) => {
      assertBlockContext(
        loggedUser = Some(DirectlyLoggedUser(user)),
        currentGroup = Some(group),
        availableGroups = availableGroups
      )(blockContext)
    }

  private def impersonatedUserOutputBlockContextAssertion(user: User.Id,
                                                          group: GroupName,
                                                          availableGroups: UniqueList[GroupName],
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
