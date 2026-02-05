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
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause.{AuthenticationFailed, ImpersonationNotAllowed}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.mocks.NoOpMocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.auth.ProxyAuthRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.{Impersonation, ImpersonationSettings}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.LoggedUser.{DirectlyLoggedUser, ImpersonatedUser}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.language.postfixOps

class ProxyAuthRuleTests
  extends AnyWordSpec with BlockContextAssertion {

  "A ProxyAuthRule" should {
    "match" when {
      "one user id is configured and the same id can be find in auth header" in {
        assertMatchRule(
          settings = ProxyAuthRule.Settings(UniqueNonEmptyList.of(User.Id("userA")), headerNameFrom("custom-user-auth-header")),
          headers = Set(headerFrom("custom-user-auth-header" -> "userA"))
        )(
          blockContextAssertion = defaultOutputBlockContextAssertion(
            User.Id("userA")
          )
        )
      }
      "several user ids are configured and one of them can be find in auth header" in {
        assertMatchRule(
          settings = ProxyAuthRule.Settings(
            UniqueNonEmptyList.of(User.Id("userA"), User.Id("userB"), User.Id("userC")),
            headerNameFrom("custom-user-auth-header")
          ),
          headers = Set(headerFrom("custom-user-auth-header" -> "userB"))
        )(
          blockContextAssertion = defaultOutputBlockContextAssertion(
            User.Id("userB")
          )
        )
      }
      "user is being impersonated" when {
        "impersonation is enabled" when {
          "impersonated user is on allowed users list" in {
            assertMatchRule(
              settings = ProxyAuthRule.Settings(UniqueNonEmptyList.of(User.Id("userA")), headerNameFrom("custom-user-auth-header")),
              headers = Set(basicAuthHeader("admin:pass"), impersonationHeader("userA")),
              impersonation = Impersonation.Enabled(ImpersonationSettings(
                impersonators = List(impersonatorDefFrom(
                  userIdPattern = "*",
                  impersonatorCredentials = Credentials(User.Id("admin"), PlainTextSecret("pass")),
                  impersonatedUsersIdPatterns = NonEmptyList.of("userA")
                )),
                mocksProvider = NoOpMocksProvider // not needed in this context
              )),
            )(
              blockContextAssertion = impersonatedUserOutputBlockContextAssertion(
                user = User.Id("userA"),
                impersonator = User.Id("admin")
              )
            )
          }
        }
      }
    }
    "not match" when {
      "none of configured user ids corresponds to the auth header one" in {
        assertNotMatchRule(
          settings = ProxyAuthRule.Settings(
            UniqueNonEmptyList.of(User.Id("userA"), User.Id("userB"), User.Id("userC")),
            headerNameFrom("custom-user-auth-header")
          ),
          headers = Set(headerFrom("custom-user-auth-header" -> "userD")),
          denialCause = AuthenticationFailed("User 'userD' not in allowed users list")
        )
      }
      "user id is passed in different header than the configured one" in {
        assertNotMatchRule(
          settings = ProxyAuthRule.Settings(
            UniqueNonEmptyList.of(User.Id("userA")),
            headerNameFrom("custom-user-auth-header")
          ),
          headers = Set(headerFrom("X-Forwarded-User" -> "userD")),
          denialCause = AuthenticationFailed("User header 'custom-user-auth-header' not found")
        )
      }
      "user is being impersonated" when {
        "impersonation is enabled" when {
          "admin cannot be authenticated" in {
            assertNotMatchRule(
              settings = ProxyAuthRule.Settings(UniqueNonEmptyList.of(User.Id("userA")), headerNameFrom("custom-user-auth-header")),
              headers = Set(basicAuthHeader("admin:wrong_pass"), impersonationHeader("userA")),
              impersonation = Impersonation.Enabled(ImpersonationSettings(
                impersonators = List(impersonatorDefFrom(
                  userIdPattern = "*",
                  impersonatorCredentials = Credentials(User.Id("admin"), PlainTextSecret("pass")),
                  impersonatedUsersIdPatterns = NonEmptyList.of("userA")
                )),
                mocksProvider = NoOpMocksProvider // not needed in this context
              )),
              denialCause = ImpersonationNotAllowed
            )
          }
          "admin cannot impersonate the given user" in {
            assertNotMatchRule(
              settings = ProxyAuthRule.Settings(UniqueNonEmptyList.of(User.Id("userA")), headerNameFrom("custom-user-auth-header")),
              headers = Set(basicAuthHeader("admin:pass"), impersonationHeader("userA")),
              impersonation = Impersonation.Enabled(ImpersonationSettings(
                impersonators = List(impersonatorDefFrom(
                  userIdPattern = "*",
                  impersonatorCredentials = Credentials(User.Id("admin"), PlainTextSecret("pass")),
                  impersonatedUsersIdPatterns = NonEmptyList.of("userB")
                )),
                mocksProvider = NoOpMocksProvider // not needed in this context
              )),
              denialCause = ImpersonationNotAllowed
            )
          }
          "rule doesn't accept given impersonated user" in {
            assertNotMatchRule(
              settings = ProxyAuthRule.Settings(UniqueNonEmptyList.of(User.Id("userB")), headerNameFrom("custom-user-auth-header")),
              headers = Set(basicAuthHeader("admin:pass"), impersonationHeader("userA")),
              impersonation = Impersonation.Enabled(ImpersonationSettings(
                impersonators = List(impersonatorDefFrom(
                  userIdPattern = "*",
                  impersonatorCredentials = Credentials(User.Id("admin"), PlainTextSecret("pass")),
                  impersonatedUsersIdPatterns = NonEmptyList.of("userA")
                )),
                mocksProvider = NoOpMocksProvider // not needed in this context
              )),
              denialCause = AuthenticationFailed("Impersonated user does not exist")
            )
          }
        }
      }
    }
  }

  private def assertMatchRule(settings: ProxyAuthRule.Settings,
                              impersonation: Impersonation = Impersonation.Disabled,
                              headers: Set[Header])
                             (blockContextAssertion: BlockContext => Unit): Unit =
    assertRule(settings, impersonation, headers, RuleCheckAssertion.RulePermitted(blockContextAssertion))

  private def assertNotMatchRule(settings: ProxyAuthRule.Settings,
                                 impersonation: Impersonation = Impersonation.Disabled,
                                 headers: Set[Header],
                                 denialCause: Cause): Unit =
    assertRule(settings, impersonation, headers, RuleCheckAssertion.RuleDenied(denialCause))

  private def assertRule(settings: ProxyAuthRule.Settings,
                         impersonation: Impersonation,
                         headers: Set[Header],
                         assertionType: RuleCheckAssertion): Unit = {
    val rule = new ProxyAuthRule(settings, CaseSensitivity.Enabled, impersonation)
    val requestContext = MockRequestContext.indices.withHeaders(headers)
    val blockContext = CurrentUserMetadataRequestBlockContext(
      requestContext = requestContext,
      userMetadata = UserMetadata.from(requestContext),
      responseHeaders = Set.empty,
      responseTransformations = List.empty
    )
    rule.checkAndAssert(blockContext, assertionType)
  }

  private def defaultOutputBlockContextAssertion(user: User.Id): BlockContext => Unit =
    (blockContext: BlockContext) => {
      assertBlockContext(
        loggedUser = Some(DirectlyLoggedUser(user))
      )(blockContext)
    }

  private def impersonatedUserOutputBlockContextAssertion(user: User.Id,
                                                          impersonator: User.Id): BlockContext => Unit =
    (blockContext: BlockContext) => {
      assertBlockContext(
        loggedUser = Some(ImpersonatedUser(user, impersonator))
      )(blockContext)
    }

}
