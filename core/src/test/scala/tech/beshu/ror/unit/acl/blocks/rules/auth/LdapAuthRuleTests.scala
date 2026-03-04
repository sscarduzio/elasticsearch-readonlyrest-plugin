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
import monix.eval.Task
import org.scalamock.scalatest.MockFactory
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause.{AuthenticationFailed, GroupsAuthorizationFailed}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.*
import tech.beshu.ror.accesscontrol.blocks.metadata.BlockMetadata
import tech.beshu.ror.accesscontrol.blocks.mocks.NoOpMocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.auth.LdapAuthorizationRule.Settings.{NegativeGroupsLogicSettings, PositiveGroupsLogicSettings}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.{Impersonation, ImpersonationSettings}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.{LdapAuthRule, LdapAuthenticationRule, LdapAuthorizationRule}
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.LoggedUser.{DirectlyLoggedUser, ImpersonatedUser}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.WithDummyRequestIdSupport
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

import scala.language.postfixOps

class LdapAuthRuleTests
  extends AnyWordSpec
    with MockFactory
    with BlockContextAssertion
    with WithDummyRequestIdSupport {

  "An LdapAuthRule" should {
    "match" when {
      "groups OR logic is used" when {
        "at least one allowed group matches the LDAP groups (1)" in {
          assertMatchRule(
            authenticationSettings = LdapAuthenticationRule.Settings(
              mockLdapAuthenticationService(User.Id("user1"), PlainTextSecret("pass"), true)
            ),
            authorizationSettings = ldapAuthorizationRuleSettings(
              ldap = mockLdapAuthorizationService(User.Id("user1"), Task.now(UniqueList.of(group("g1")))),
              groupsLogic = GroupsLogic.AnyOf(GroupIds(
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
              mockLdapAuthenticationService(User.Id("user1"), PlainTextSecret("pass"), true)
            ),
            authorizationSettings = ldapAuthorizationRuleSettings(
              ldap = mockLdapAuthorizationService(User.Id("user1"), Task.now(UniqueList.of(group("g1"), group("g2")))),
              groupsLogic = GroupsLogic.AnyOf(GroupIds(
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
              mockLdapAuthenticationService(User.Id("user1"), PlainTextSecret("pass"), true)
            ),
            authorizationSettings = ldapAuthorizationRuleSettings(
              ldap = mockLdapAuthorizationService(User.Id("user1"), Task.now(UniqueList.of(group("g1"), group("g2")))),
              groupsLogic = GroupsLogic.AllOf(GroupIds(
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
              mockLdapAuthenticationService(User.Id("user1"), PlainTextSecret("pass"), true)
            ),
            authorizationSettings = ldapAuthorizationRuleSettings(
              ldap = mockLdapAuthorizationService(User.Id("user1"), Task.now(UniqueList.of(group("g1"), group("g2")))),
              groupsLogic = GroupsLogic.AllOf(GroupIds(
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
      "groups NOT_ANY_OF logic is used" when {
        "none of the forbidden groups match the LDAP groups" in {
          assertMatchRule(
            authenticationSettings = LdapAuthenticationRule.Settings(
              mockLdapAuthenticationService(User.Id("user1"), PlainTextSecret("pass"), true)
            ),
            authorizationSettings = ldapAuthorizationRuleSettings(
              ldap = mockLdapAuthorizationService(User.Id("user1"), Task.now(UniqueList.of(group("g1"), group("g2")))),
              groupsLogic = GroupsLogic.NotAnyOf(GroupIds(
                UniqueNonEmptyList.of(GroupId("g3"), GroupId("g4"))
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
      "groups NOT_ALL_OF logic is used" when {
        "none of the forbidden groups match the LDAP groups" in {
          assertMatchRule(
            authenticationSettings = LdapAuthenticationRule.Settings(
              mockLdapAuthenticationService(User.Id("user1"), PlainTextSecret("pass"), true)
            ),
            authorizationSettings = ldapAuthorizationRuleSettings(
              ldap = mockLdapAuthorizationService(User.Id("user1"), Task.now(UniqueList.of(group("g1"), group("g2")))),
              groupsLogic = GroupsLogic.NotAllOf(GroupIds(
                UniqueNonEmptyList.of(GroupId("g3"), GroupId("g4"))
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
        "1 of 2 forbidden groups match the LDAP groups" in {
          assertMatchRule(
            authenticationSettings = LdapAuthenticationRule.Settings(
              mockLdapAuthenticationService(User.Id("user1"), PlainTextSecret("pass"), true)
            ),
            authorizationSettings = ldapAuthorizationRuleSettings(
              ldap = mockLdapAuthorizationService(User.Id("user1"), Task.now(UniqueList.of(group("g1"), group("g2")))),
              groupsLogic = GroupsLogic.NotAllOf(GroupIds(
                UniqueNonEmptyList.of(GroupId("g2"), GroupId("g3"))
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
              authorizationSettings = ldapAuthorizationRuleSettings(
                mockLdapAuthorizationService(LdapService.Name("ldap1")),
                groupsLogic = GroupsLogic.AnyOf(GroupIds(
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
          authorizationSettings = ldapAuthorizationRuleSettings(
            ldap = mock[LdapAuthorizationService.WithoutGroupsFiltering],
            groupsLogic = GroupsLogic.AnyOf(GroupIds(
              UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
            ))
          ),
          basicHeader = None,
          denialCause = AuthenticationFailed("No basic auth credentials provided")
        )
      }
      "user cannot be authenticated" in {
        assertNotMatchRule(
          authenticationSettings = LdapAuthenticationRule.Settings(
            mockLdapAuthenticationService(User.Id("user1"), PlainTextSecret("pass"), false)
          ),
          authorizationSettings = ldapAuthorizationRuleSettings(
            ldap = mock[LdapAuthorizationService.WithoutGroupsFiltering],
            groupsLogic = GroupsLogic.AnyOf(GroupIds(
              UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
            ))
          ),
          basicHeader = Some(basicAuthHeader("user1:pass")),
          denialCause = AuthenticationFailed("mocked - auth failed")
        )
      }
      "user doesn't have any permitted group" in {
        assertNotMatchRule(
          authenticationSettings = LdapAuthenticationRule.Settings(
            mockLdapAuthenticationService(User.Id("user1"), PlainTextSecret("pass"), true)
          ),
          authorizationSettings = ldapAuthorizationRuleSettings(
            ldap = mockLdapAuthorizationService(User.Id("user1"), Task.now(UniqueList.empty)),
            groupsLogic = GroupsLogic.AnyOf(GroupIds(
              UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
            ))
          ),
          basicHeader = Some(basicAuthHeader("user1:pass")),
          denialCause = GroupsAuthorizationFailed("User has no groups")
        )
      }
      "groups AND logic is used and not all configured groups are matched" in {
        assertNotMatchRule(
          authenticationSettings = LdapAuthenticationRule.Settings(
            mockLdapAuthenticationService(User.Id("user1"), PlainTextSecret("pass"), true)
          ),
          authorizationSettings = ldapAuthorizationRuleSettings(
            ldap = mockLdapAuthorizationService(User.Id("user1"), Task.now(UniqueList.of(group("g1")))),
            groupsLogic = GroupsLogic.AllOf(GroupIds(
              UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
            ))
          ),
          basicHeader = Some(basicAuthHeader("user1:pass")),
          denialCause = GroupsAuthorizationFailed("None of the user's groups match the configured groups")
        )
      }
      "groups NOT_ANY_OF logic is used and 1 of 2 forbidden groups is matched" in {
        assertNotMatchRule(
          authenticationSettings = LdapAuthenticationRule.Settings(
            mockLdapAuthenticationService(User.Id("user1"), PlainTextSecret("pass"), true)
          ),
          authorizationSettings = ldapAuthorizationRuleSettings(
            ldap = mockLdapAuthorizationService(User.Id("user1"), Task.now(UniqueList.of(group("g1")))),
            groupsLogic = GroupsLogic.NotAnyOf(GroupIds(
              UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
            ))
          ),
          basicHeader = Some(basicAuthHeader("user1:pass")),
          denialCause = GroupsAuthorizationFailed("None of the user's groups match the configured groups")
        )
      }
      "groups NOT_ANY_OF logic is used and all 2 forbidden groups are matched among 2 groups in LDAP" in {
        assertNotMatchRule(
          authenticationSettings = LdapAuthenticationRule.Settings(
            mockLdapAuthenticationService(User.Id("user1"), PlainTextSecret("pass"), true)
          ),
          authorizationSettings = ldapAuthorizationRuleSettings(
            ldap = mockLdapAuthorizationService(User.Id("user1"), Task.now(UniqueList.of(group("g1"), group("g2")))),
            groupsLogic = GroupsLogic.NotAnyOf(GroupIds(
              UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
            ))
          ),
          basicHeader = Some(basicAuthHeader("user1:pass")),
          denialCause = GroupsAuthorizationFailed("None of the user's groups match the configured groups")
        )
      }
      "groups NOT_ANY_OF logic is used and all 2 forbidden groups are matched among 3 groups in LDAP" in {
        assertNotMatchRule(
          authenticationSettings = LdapAuthenticationRule.Settings(
            mockLdapAuthenticationService(User.Id("user1"), PlainTextSecret("pass"), true)
          ),
          authorizationSettings = ldapAuthorizationRuleSettings(
            ldap = mockLdapAuthorizationService(User.Id("user1"), Task.now(UniqueList.of(group("g1"), group("g2"), group("g3")))),
            groupsLogic = GroupsLogic.NotAnyOf(GroupIds(
              UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
            ))
          ),
          basicHeader = Some(basicAuthHeader("user1:pass")),
          denialCause = GroupsAuthorizationFailed("None of the user's groups match the configured groups")
        )
      }
      "groups NOT_ALL_OF logic is used and all 2 forbidden groups are matched among 2 groups in LDAP" in {
        assertNotMatchRule(
          authenticationSettings = LdapAuthenticationRule.Settings(
            mockLdapAuthenticationService(User.Id("user1"), PlainTextSecret("pass"), true)
          ),
          authorizationSettings = ldapAuthorizationRuleSettings(
            ldap = mockLdapAuthorizationService(User.Id("user1"), Task.now(UniqueList.of(group("g1"), group("g2")))),
            groupsLogic = GroupsLogic.NotAllOf(GroupIds(
              UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
            ))
          ),
          basicHeader = Some(basicAuthHeader("user1:pass")),
          denialCause = GroupsAuthorizationFailed("None of the user's groups match the configured groups")
        )
      }
      "groups NOT_ALL_OF logic is used and all 2 forbidden groups are matched among 3 groups in LDAP" in {
        assertNotMatchRule(
          authenticationSettings = LdapAuthenticationRule.Settings(
            mockLdapAuthenticationService(User.Id("user1"), PlainTextSecret("pass"), true)
          ),
          authorizationSettings = ldapAuthorizationRuleSettings(
            ldap = mockLdapAuthorizationService(User.Id("user1"), Task.now(UniqueList.of(group("g1"), group("g2"), group("g3")))),
            groupsLogic = GroupsLogic.NotAllOf(GroupIds(
              UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
            ))
          ),
          basicHeader = Some(basicAuthHeader("user1:pass")),
          denialCause = GroupsAuthorizationFailed("None of the user's groups match the configured groups")
        )
      }
      "LDAP authentication fails" in {
        assertRuleThrown(
          authenticationSettings = LdapAuthenticationRule.Settings(
            mockLdapAuthenticationService(User.Id("user1"), PlainTextSecret("pass"), Task.raiseError(TestException("authentication failure")))
          ),
          authorizationSettings = ldapAuthorizationRuleSettings(
            ldap = mock[LdapAuthorizationService.WithoutGroupsFiltering],
            groupsLogic = GroupsLogic.AnyOf(GroupIds(
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
            mockLdapAuthenticationService(User.Id("user1"), PlainTextSecret("pass"), true)
          ),
          authorizationSettings = ldapAuthorizationRuleSettings(
            ldap = mockLdapAuthorizationService(User.Id("user1"), Task.raiseError(TestException("authorization failure"))),
            groupsLogic = GroupsLogic.AnyOf(GroupIds(
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
              authorizationSettings = ldapAuthorizationRuleSettings(
                ldap = mock[LdapAuthorizationService.WithoutGroupsFiltering],
                groupsLogic = GroupsLogic.AnyOf(GroupIds(
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
              denialCause = Cause.ImpersonationNotAllowed
            )
          }
          "admin cannot impersonate the given user" in {
            assertNotMatchRule(
              authenticationSettings = LdapAuthenticationRule.Settings(mock[LdapAuthenticationService]),
              authorizationSettings = ldapAuthorizationRuleSettings(
                ldap = mock[LdapAuthorizationService.WithoutGroupsFiltering],
                groupsLogic = GroupsLogic.AnyOf(GroupIds(
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
              denialCause = Cause.ImpersonationNotAllowed
            )
          }
          "mocks provider doesn't have the given user" in {
            assertNotMatchRule(
              authenticationSettings = LdapAuthenticationRule.Settings(mock[LdapAuthenticationService]),
              authorizationSettings = ldapAuthorizationRuleSettings(
                ldap = mock[LdapAuthorizationService.WithoutGroupsFiltering],
                groupsLogic = GroupsLogic.AnyOf(GroupIds(
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
              denialCause = Cause.ImpersonationNotAllowed
            )
          }
          "mocks provider has a given user, but he doesn't have proper group" in {
            assertNotMatchRule(
              authenticationSettings = LdapAuthenticationRule.Settings(mock[LdapAuthenticationService]),
              authorizationSettings = ldapAuthorizationRuleSettings(
                ldap = mock[LdapAuthorizationService.WithoutGroupsFiltering],
                groupsLogic = GroupsLogic.AnyOf(GroupIds(
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
              denialCause = Cause.ImpersonationNotAllowed
            )
          }
          "mocks provider is unavailable" in {
            assertNotMatchRule(
              authenticationSettings = LdapAuthenticationRule.Settings(mock[LdapAuthenticationService]),
              authorizationSettings = ldapAuthorizationRuleSettings(
                ldap = mock[LdapAuthorizationService.WithoutGroupsFiltering],
                groupsLogic = GroupsLogic.AnyOf(GroupIds(
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
              denialCause = Cause.ImpersonationNotAllowed
            )
          }
        }
        "impersonation is disabled" when {
          "admin is trying to impersonate user" in {
            assertNotMatchRule(
              authenticationSettings = LdapAuthenticationRule.Settings(
                mockLdapAuthenticationService(User.Id("admin"), PlainTextSecret("pass"), false)
              ),
              authorizationSettings = ldapAuthorizationRuleSettings(
                ldap = mock[LdapAuthorizationService.WithoutGroupsFiltering],
                groupsLogic = GroupsLogic.AnyOf(GroupIds(
                  UniqueNonEmptyList.of(GroupId("g1"), GroupId("g2"))
                ))
              ),
              impersonation = Impersonation.Disabled,
              basicHeader = Some(basicAuthHeader("admin:pass")),
              impersonateAsHeader = Some(impersonationHeader("user1")),
              denialCause = AuthenticationFailed("mocked - auth failed")
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
      impersonateAsHeader.toCovariantSet + basicHeader,
      RuleCheckAssertion.RulePermitted(blockContextAssertion)
    )

  private def assertNotMatchRule(authenticationSettings: LdapAuthenticationRule.Settings,
                                 authorizationSettings: LdapAuthorizationRule.Settings,
                                 impersonation: Impersonation = Impersonation.Disabled,
                                 basicHeader: Option[Header],
                                 impersonateAsHeader: Option[Header] = None,
                                 denialCause: Cause): Unit =
    assertRule(
      authenticationSettings,
      authorizationSettings,
      impersonation,
      impersonateAsHeader.toCovariantSet ++ basicHeader.toSet,
      RuleCheckAssertion.RuleDenied(denialCause)
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
      RuleCheckAssertion.RuleThrownException(exception)
    )

  private def assertRule(authenticationSettings: LdapAuthenticationRule.Settings,
                         authorizationSettings: LdapAuthorizationRule.Settings,
                         impersonation: Impersonation,
                         headers: Set[Header],
                         assertionType: RuleCheckAssertion): Unit = {
    val rule = new LdapAuthRule(
      authentication = new LdapAuthenticationRule(authenticationSettings, CaseSensitivity.Enabled, impersonation),
      authorization = new LdapAuthorizationRule(authorizationSettings, CaseSensitivity.Enabled, impersonation)
    )
    val requestContext = MockRequestContext.indices.withHeaders(headers)
    val blockContext = GeneralIndexRequestBlockContext(
      block = mock[Block],
      requestContext = requestContext,
      blockMetadata = BlockMetadata.from(requestContext),
      responseHeaders = Set.empty,
      responseTransformations = List.empty,
      filteredIndices = Set.empty,
      allAllowedIndices = Set.empty,
      allAllowedClusters = Set.empty
    )
    rule.checkAndAssert(blockContext, assertionType)
  }

  private def mockLdapAuthenticationService(user: User.Id, secret: PlainTextSecret, result: Task[Boolean]): LdapAuthenticationService = {
    val service = mock[LdapAuthenticationService]
    (service.authenticate(_: User.Id, _: PlainTextSecret)(_: RequestId))
      .expects(user, secret, *)
      .returning(result.map {
        case true => Right(DirectlyLoggedUser(user))
        case false => Left(AuthenticationFailed("mocked - auth failed"))
      })
    service
  }

  private def mockLdapAuthenticationService(user: User.Id, secret: PlainTextSecret, result: Boolean): LdapAuthenticationService = {
    mockLdapAuthenticationService(user, secret, Task.now(result))
  }

  private def mockLdapAuthenticationService(ldapId: LdapService#Id): LdapAuthenticationService = {
    val service = mock[LdapAuthenticationService]
    (() => service.id).expects().returning(ldapId)
    service
  }

  private def mockLdapAuthorizationService(user: User.Id, result: Task[UniqueList[Group]]): LdapAuthorizationService.WithoutGroupsFiltering = {
    val service = mock[LdapAuthorizationService.WithoutGroupsFiltering]
    (service.groupsOf(_: User.Id)(_: RequestId))
      .expects(user, *)
      .returning(result)
    service
  }

  private def mockLdapAuthorizationService(ldapId: LdapService#Id): LdapAuthorizationService.WithoutGroupsFiltering = {
    val service = mock[LdapAuthorizationService.WithoutGroupsFiltering]
    (() => service.id).expects().returning(ldapId)
    service
  }

  private def defaultOutputBlockContextAssertion(user: User.Id,
                                                 group: GroupId,
                                                 availableGroups: UniqueList[Group]): BlockContext => Unit =
    (blockContext: BlockContext) => {
      assertBlockContext(blockContext)(
        loggedUser = Some(DirectlyLoggedUser(user)),
        currentGroup = Some(group),
        availableGroups = availableGroups
      )
    }

  private def impersonatedUserOutputBlockContextAssertion(user: User.Id,
                                                          group: GroupId,
                                                          availableGroups: UniqueList[Group],
                                                          impersonator: User.Id): BlockContext => Unit =
    (blockContext: BlockContext) => {
      assertBlockContext(blockContext)(
        loggedUser = Some(ImpersonatedUser(user, impersonator)),
        currentGroup = Some(group),
        availableGroups = availableGroups
      )
    }

  private sealed case class TestException(message: String) extends Exception(message)
}