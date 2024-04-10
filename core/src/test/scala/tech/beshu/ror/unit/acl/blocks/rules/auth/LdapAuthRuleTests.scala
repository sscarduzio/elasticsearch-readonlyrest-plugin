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
import eu.timepit.refined.auto._
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap._
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.mocks.NoOpMocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.{Impersonation, ImpersonationSettings}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.{LdapAuthRule, LdapAuthenticationRule, LdapAuthorizationRule}
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.LoggedUser.{DirectlyLoggedUser, ImpersonatedUser}
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.utils.WithDummyRequestIdSupport
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

class LdapAuthRuleTests
  extends AnyWordSpec
    with Inside
    with MockFactory
    with BlockContextAssertion
    with WithDummyRequestIdSupport {

  "An LdapAuthRule" should {
    "match" when {
      "groups OR logic is used" when {
        "at least one allowed group matches the LDAP groups (1)" in {
          assertMatchRule(
            authenticationSettings = LdapAuthenticationRule.Settings(
              mockLdapAuthenticationService(User.Id("user1"), PlainTextSecret("pass"), Task.now(true))
            ),
            authorizationSettings = LdapAuthorizationRule.Settings(
              ldap = mockLdapAuthorizationService(User.Id("user1"), Task.now(UniqueList.of(group("g1")))),
              permittedGroupsLogic = GroupsLogic.Or(PermittedGroupIds(
                UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
              )),
            ),
            basicHeader = basicAuthHeader("user1:pass")
          )(
            blockContextAssertion = defaultOutputBlockContextAssertion(
              user = User.Id("user1"),
              group = GroupId("g1"),
              availableGroups = UniqueList.of(group("g1"))
            )
          )
        }
        "at least one allowed group matches the LDAP groups (2)" in {
          assertMatchRule(
            authenticationSettings = LdapAuthenticationRule.Settings(
              mockLdapAuthenticationService(User.Id("user1"), PlainTextSecret("pass"), Task.now(true))
            ),
            authorizationSettings = LdapAuthorizationRule.Settings(
              ldap = mockLdapAuthorizationService(User.Id("user1"), Task.now(UniqueList.of(group("g1"), group("g2")))),
              permittedGroupsLogic = GroupsLogic.Or(PermittedGroupIds(
                UniqueNonEmptyList.of(GroupIdLike.from("*1"), GroupId("g2"))
              )),
            ),
            basicHeader = basicAuthHeader("user1:pass")
          )(
            blockContextAssertion = defaultOutputBlockContextAssertion(
              user = User.Id("user1"),
              group = GroupId("g1"),
              availableGroups = UniqueList.of(group("g1"), group("g2"))
            )
          )
        }
      }
      "groups AND logic is used" when {
        "all allowed groups match the LDAP groups (1)" in {
          assertMatchRule(
            authenticationSettings = LdapAuthenticationRule.Settings(
              mockLdapAuthenticationService(User.Id("user1"), PlainTextSecret("pass"), Task.now(true))
            ),
            authorizationSettings = LdapAuthorizationRule.Settings(
              ldap = mockLdapAuthorizationService(User.Id("user1"), Task.now(UniqueList.of(group("g1"), group("g2")))),
              permittedGroupsLogic = GroupsLogic.And(PermittedGroupIds(
                UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
              )),
            ),
            basicHeader = basicAuthHeader("user1:pass")
          )(
            blockContextAssertion = defaultOutputBlockContextAssertion(
              user = User.Id("user1"),
              group = GroupId("g1"),
              availableGroups = UniqueList.of(group("g1"), group("g2"))
            )
          )
        }
        "all allowed groups match the LDAP groups (2)" in {
          assertMatchRule(
            authenticationSettings = LdapAuthenticationRule.Settings(
              mockLdapAuthenticationService(User.Id("user1"), PlainTextSecret("pass"), Task.now(true))
            ),
            authorizationSettings = LdapAuthorizationRule.Settings(
              ldap = mockLdapAuthorizationService(User.Id("user1"), Task.now(UniqueList.of(group("g1"), group("g2")))),
              permittedGroupsLogic = GroupsLogic.And(PermittedGroupIds(
                UniqueNonEmptyList.of(GroupIdLike.from("*1"), GroupId("g2"))
              )),
            ),
            basicHeader = basicAuthHeader("user1:pass")
          )(
            blockContextAssertion = defaultOutputBlockContextAssertion(
              user = User.Id("user1"),
              group = GroupId("g1"),
              availableGroups = UniqueList.of(group("g1"), group("g2"))
            )
          )
        }
      }
      "user is being impersonated" when {
        "impersonation is enabled" when {
          "mocks provider has a given user with allowed groups" in {
            assertMatchRule(
              authenticationSettings = LdapAuthenticationRule.Settings(
                mockLdapAuthenticationService(LdapService.Name("ldap1"))
              ),
              authorizationSettings = LdapAuthorizationRule.Settings(
                mockLdapAuthorizationService(LdapService.Name("ldap1")),
                permittedGroupsLogic = GroupsLogic.Or(PermittedGroupIds(
                  UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
                ))
              ),
              impersonation = Impersonation.Enabled(ImpersonationSettings(
                impersonators = List(impersonatorDefFrom(
                  userIdPattern = "*",
                  impersonatorCredentials = Credentials(User.Id("admin"), PlainTextSecret("pass")),
                  impersonatedUsersIdPatterns = NonEmptyList.of("user1")
                )),
                mocksProvider = mocksProviderForLdapFrom(Map(
                  LdapService.Name("ldap1") -> Map(User.Id("user1") -> Set(group("g1")))
                ))
              )),
              basicHeader = basicAuthHeader("admin:pass"),
              impersonateAsHeader = Some(impersonationHeader("user1"))
            )(
              blockContextAssertion = impersonatedUserOutputBlockContextAssertion(
                user = User.Id("user1"),
                group = GroupId("g1"),
                availableGroups = UniqueList.of(group("g1")),
                impersonator = User.Id("admin")
              )
            )
          }
        }
      }
    }
    "not match" when {
      "basic auth header is not passed" in {
        assertNotMatchRule(
          authenticationSettings = LdapAuthenticationRule.Settings(mock[LdapAuthenticationService]),
          authorizationSettings = LdapAuthorizationRule.Settings(
            ldap = mock[LdapAuthorizationServiceWithGroupsFiltering],
            permittedGroupsLogic = GroupsLogic.Or(PermittedGroupIds(
              UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
            ))
          ),
          basicHeader = None
        )
      }
      "user cannot be authenticated" in {
        assertNotMatchRule(
          authenticationSettings = LdapAuthenticationRule.Settings(
            mockLdapAuthenticationService(User.Id("user1"), PlainTextSecret("pass"), Task.now(false))
          ),
          authorizationSettings = LdapAuthorizationRule.Settings(
            ldap = mock[LdapAuthorizationServiceWithGroupsFiltering],
            permittedGroupsLogic = GroupsLogic.Or(PermittedGroupIds(
              UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
            ))
          ),
          basicHeader = Some(basicAuthHeader("user1:pass"))
        )
      }
      "user doesn't have any permitted group" in {
        assertNotMatchRule(
          authenticationSettings = LdapAuthenticationRule.Settings(
            mockLdapAuthenticationService(User.Id("user1"), PlainTextSecret("pass"), Task.now(true))
          ),
          authorizationSettings = LdapAuthorizationRule.Settings(
            ldap = mockLdapAuthorizationService(User.Id("user1"), Task.now(UniqueList.empty)),
            permittedGroupsLogic = GroupsLogic.Or(PermittedGroupIds(
              UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
            ))
          ),
          basicHeader = Some(basicAuthHeader("user1:pass"))
        )
      }
      "groups AND logic is used and not all configured groups are matched" in {
        assertNotMatchRule(
          authenticationSettings = LdapAuthenticationRule.Settings(
            mockLdapAuthenticationService(User.Id("user1"), PlainTextSecret("pass"), Task.now(true))
          ),
          authorizationSettings = LdapAuthorizationRule.Settings(
            ldap = mockLdapAuthorizationService(User.Id("user1"), Task.now(UniqueList.of(group("g1")))),
            permittedGroupsLogic = GroupsLogic.And(PermittedGroupIds(
              UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
            ))
          ),
          basicHeader = Some(basicAuthHeader("user1:pass"))
        )
      }
      "LDAP authentication fails" in {
        assertRuleThrown(
          authenticationSettings = LdapAuthenticationRule.Settings(
            mockLdapAuthenticationService(User.Id("user1"), PlainTextSecret("pass"), Task.raiseError(TestException("authentication failure")))
          ),
          authorizationSettings = LdapAuthorizationRule.Settings(
            ldap = mock[LdapAuthorizationServiceWithGroupsFiltering],
            permittedGroupsLogic = GroupsLogic.Or(PermittedGroupIds(
              UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
            ))
          ),
          basicHeader = basicAuthHeader("user1:pass"),
          exception = TestException("authentication failure")
        )
      }
      "LDAP authorization fails" in {
        assertRuleThrown(
          authenticationSettings = LdapAuthenticationRule.Settings(
            mockLdapAuthenticationService(User.Id("user1"), PlainTextSecret("pass"), Task.now(true))
          ),
          authorizationSettings = LdapAuthorizationRule.Settings(
            ldap = mockLdapAuthorizationService(User.Id("user1"), Task.raiseError(TestException("authorization failure"))),
            permittedGroupsLogic = GroupsLogic.Or(PermittedGroupIds(
              UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
            ))
          ),
          basicHeader = basicAuthHeader("user1:pass"),
          exception = TestException("authorization failure")
        )
      }
      "user is being impersonated" when {
        "impersonation is enabled" when {
          "admin cannot be authenticated" in {
            assertNotMatchRule(
              authenticationSettings = LdapAuthenticationRule.Settings(mock[LdapAuthenticationService]),
              authorizationSettings = LdapAuthorizationRule.Settings(
                ldap = mock[LdapAuthorizationServiceWithGroupsFiltering],
                permittedGroupsLogic = GroupsLogic.Or(PermittedGroupIds(
                  UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
                ))
              ),
              impersonation = Impersonation.Enabled(ImpersonationSettings(
                impersonators = List(impersonatorDefFrom(
                  userIdPattern = "*",
                  impersonatorCredentials = Credentials(User.Id("admin"), PlainTextSecret("password")),
                  impersonatedUsersIdPatterns = NonEmptyList.of("user1")
                )),
                mocksProvider = mocksProviderForLdapFrom(Map(
                  LdapService.Name("ldap1") -> Map(User.Id("user1") -> Set(group("g1")))
                ))
              )),
              basicHeader = Some(basicAuthHeader("admin:pass")),
              impersonateAsHeader = Some(impersonationHeader("user1")),
              rejectionCause = Some(Cause.ImpersonationNotAllowed)
            )
          }
          "admin cannot impersonate the given user" in {
            assertNotMatchRule(
              authenticationSettings = LdapAuthenticationRule.Settings(mock[LdapAuthenticationService]),
              authorizationSettings = LdapAuthorizationRule.Settings(
                ldap = mock[LdapAuthorizationServiceWithGroupsFiltering],
                permittedGroupsLogic = GroupsLogic.Or(PermittedGroupIds(
                  UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
                ))
              ),
              impersonation = Impersonation.Enabled(ImpersonationSettings(
                impersonators = List(impersonatorDefFrom(
                  userIdPattern = "*_1",
                  impersonatorCredentials = Credentials(User.Id("admin"), PlainTextSecret("password")),
                  impersonatedUsersIdPatterns = NonEmptyList.of("user_1")
                )),
                mocksProvider = mocksProviderForLdapFrom(Map(
                  LdapService.Name("ldap1") -> Map(User.Id("user1") -> Set(group("g1")))
                ))
              )),
              basicHeader = Some(basicAuthHeader("admin:pass")),
              impersonateAsHeader = Some(impersonationHeader("user1")),
              rejectionCause = Some(Cause.ImpersonationNotAllowed)
            )
          }
          "mocks provider doesn't have the given user" in {
            assertNotMatchRule(
              authenticationSettings = LdapAuthenticationRule.Settings(mock[LdapAuthenticationService]),
              authorizationSettings = LdapAuthorizationRule.Settings(
                ldap = mock[LdapAuthorizationServiceWithGroupsFiltering],
                permittedGroupsLogic = GroupsLogic.Or(PermittedGroupIds(
                  UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
                ))
              ),
              impersonation = Impersonation.Enabled(ImpersonationSettings(
                impersonators = List(impersonatorDefFrom(
                  userIdPattern = "*",
                  impersonatorCredentials = Credentials(User.Id("admin"), PlainTextSecret("password")),
                  impersonatedUsersIdPatterns = NonEmptyList.of("user1")
                )),
                mocksProvider = mocksProviderForLdapFrom(Map(
                  LdapService.Name("ldap1") -> Map(User.Id("user2") -> Set(group("g1")))
                ))
              )),
              basicHeader = Some(basicAuthHeader("admin:pass")),
              impersonateAsHeader = Some(impersonationHeader("user1")),
              rejectionCause = Some(Cause.ImpersonationNotAllowed)
            )
          }
          "mocks provider has a given user, but he doesn't have proper group" in {
            assertNotMatchRule(
              authenticationSettings = LdapAuthenticationRule.Settings(mock[LdapAuthenticationService]),
              authorizationSettings = LdapAuthorizationRule.Settings(
                ldap = mock[LdapAuthorizationServiceWithGroupsFiltering],
                permittedGroupsLogic = GroupsLogic.Or(PermittedGroupIds(
                  UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
                ))
              ),
              impersonation = Impersonation.Enabled(ImpersonationSettings(
                impersonators = List(impersonatorDefFrom(
                  userIdPattern = "*",
                  impersonatorCredentials = Credentials(User.Id("admin"), PlainTextSecret("password")),
                  impersonatedUsersIdPatterns = NonEmptyList.of("user1")
                )),
                mocksProvider = mocksProviderForLdapFrom(Map(
                  LdapService.Name("ldap1") -> Map(User.Id("user1") -> Set(group("g3")))
                ))
              )),
              basicHeader = Some(basicAuthHeader("admin:pass")),
              impersonateAsHeader = Some(impersonationHeader("user1")),
              rejectionCause = Some(Cause.ImpersonationNotAllowed)
            )
          }
          "mocks provider is unavailable" in {
            assertNotMatchRule(
              authenticationSettings = LdapAuthenticationRule.Settings(mock[LdapAuthenticationService]),
              authorizationSettings = LdapAuthorizationRule.Settings(
                ldap = mock[LdapAuthorizationServiceWithGroupsFiltering],
                permittedGroupsLogic = GroupsLogic.Or(PermittedGroupIds(
                  UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
                ))
              ),
              impersonation = Impersonation.Enabled(ImpersonationSettings(
                impersonators = List(impersonatorDefFrom(
                  userIdPattern = "*_1",
                  impersonatorCredentials = Credentials(User.Id("admin"), PlainTextSecret("password")),
                  impersonatedUsersIdPatterns = NonEmptyList.of("user1")
                )),
                mocksProvider = NoOpMocksProvider
              )),
              basicHeader = Some(basicAuthHeader("admin:pass")),
              impersonateAsHeader = Some(impersonationHeader("user1")),
              rejectionCause = Some(Cause.ImpersonationNotAllowed)
            )
          }
        }
        "impersonation is disabled" when {
          "admin is trying to impersonate user" in {
            assertNotMatchRule(
              authenticationSettings = LdapAuthenticationRule.Settings(
                mockLdapAuthenticationService(User.Id("admin"), PlainTextSecret("pass"), Task.now(false))
              ),
              authorizationSettings = LdapAuthorizationRule.Settings(
                ldap = mock[LdapAuthorizationServiceWithGroupsFiltering],
                permittedGroupsLogic = GroupsLogic.Or(PermittedGroupIds(
                  UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
                ))
              ),
              impersonation = Impersonation.Disabled,
              basicHeader = Some(basicAuthHeader("admin:pass")),
              impersonateAsHeader = Some(impersonationHeader("user1"))
            )
          }
        }
      }
    }
  }

  private def assertMatchRule(authenticationSettings: LdapAuthenticationRule.Settings,
                              authorizationSettings: LdapAuthorizationRule.Settings,
                              impersonation: Impersonation = Impersonation.Disabled,
                              basicHeader: Header,
                              impersonateAsHeader: Option[Header] = None)
                             (blockContextAssertion: BlockContext => Unit): Unit =
    assertRule(
      authenticationSettings,
      authorizationSettings,
      impersonation,
      impersonateAsHeader.toSet + basicHeader,
      AssertionType.RuleFulfilled(blockContextAssertion)
    )

  private def assertNotMatchRule(authenticationSettings: LdapAuthenticationRule.Settings,
                                 authorizationSettings: LdapAuthorizationRule.Settings,
                                 impersonation: Impersonation = Impersonation.Disabled,
                                 basicHeader: Option[Header],
                                 impersonateAsHeader: Option[Header] = None,
                                 rejectionCause: Option[Cause] = None): Unit =
    assertRule(
      authenticationSettings,
      authorizationSettings,
      impersonation,
      impersonateAsHeader.toSet ++ basicHeader.toSet,
      AssertionType.RuleRejected(rejectionCause)
    )

  private def assertRuleThrown(authenticationSettings: LdapAuthenticationRule.Settings,
                               authorizationSettings: LdapAuthorizationRule.Settings,
                               impersonation: Impersonation = Impersonation.Disabled,
                               basicHeader: Header,
                               exception: Throwable): Unit =
    assertRule(
      authenticationSettings,
      authorizationSettings,
      impersonation,
      Set(basicHeader),
      AssertionType.RuleThrownException(exception)
    )

  private def assertRule(authenticationSettings: LdapAuthenticationRule.Settings,
                         authorizationSettings: LdapAuthorizationRule.Settings,
                         impersonation: Impersonation,
                         headers: Set[Header],
                         assertionType: AssertionType): Unit = {
    val rule = new LdapAuthRule(
      authentication = new LdapAuthenticationRule(authenticationSettings, CaseSensitivity.Enabled, impersonation),
      authorization = new LdapAuthorizationRule(authorizationSettings, CaseSensitivity.Enabled, impersonation)
    )
    val requestContext = MockRequestContext.indices.copy(headers = headers)
    val blockContext = GeneralIndexRequestBlockContext(
      requestContext = requestContext,
      userMetadata = UserMetadata.from(requestContext),
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

  private def mockLdapAuthenticationService(user: User.Id, secret: PlainTextSecret, result: Task[Boolean]) = {
    val service = mock[LdapAuthenticationService]
    (service.authenticate(_: User.Id, _: PlainTextSecret)(_: RequestId)).expects(user, secret, *).returning(result)
    service
  }

  private def mockLdapAuthenticationService(ldapId: LdapService#Id) = {
    val service = mock[LdapAuthenticationService]
    (() => service.id).expects().returning(ldapId)
    service
  }

  private def mockLdapAuthorizationService(user: User.Id, result: Task[UniqueList[Group]]) = {
    val service = mock[LdapAuthorizationServiceWithGroupsFiltering]
    (service.groupsOf(_: User.Id, _: Set[GroupIdLike])(_: RequestId))
      .expects(user, *, *)
      .returning(result)
    service
  }

  private def mockLdapAuthorizationService(ldapId: LdapService#Id) = {
    val service = mock[LdapAuthorizationServiceWithGroupsFiltering]
    (() => service.id).expects().returning(ldapId)
    service
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