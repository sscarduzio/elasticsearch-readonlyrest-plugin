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
import eu.timepit.refined.auto.*
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.mocks.NoOpMocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause.ImpersonationNotAllowed
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.TokenAuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.{Impersonation, ImpersonationSettings}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.LoggedUser.{DirectlyLoggedUser, ImpersonatedUser}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.*

import scala.concurrent.duration.*
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

class TokenAuthenticationRuleTests
  extends AnyWordSpec with Inside with BlockContextAssertion {

  "A TokenAuthenticationRule" should {
    "match" when {
      "token is configured and default header name is used" in {
        assertMatchRule(
          settings = TokenAuthenticationRule.Settings(
            user = User.Id("userA"),
            token = tokenFrom("Bearer abc123XYZ"),
            customHeaderName = None
          ),
          headers = Set(headerFrom("Authorization" -> "Bearer abc123XYZ"))
        )(
          blockContextAssertion = defaultOutputBlockContextAssertion(
            User.Id("userA")
          )
        )
      }
      "token and header name are configured and the same token can be find in auth header" in {
        assertMatchRule(
          settings = TokenAuthenticationRule.Settings(
            user = User.Id("userA"),
            token = tokenFrom("Bearer abc123XYZ"),
            tokenHeaderName = headerNameFrom("custom-user-auth-header")
          ),
          headers = Set(headerFrom("custom-user-auth-header" -> "Bearer abc123XYZ"))
        )(
          blockContextAssertion = defaultOutputBlockContextAssertion(
            User.Id("userA")
          )
        )
      }
      "token and header name are configured and the same token can be find in auth header (header name case ignoring)" in {
        assertMatchRule(
          settings = TokenAuthenticationRule.Settings(
            user = User.Id("userA"),
            token = tokenFrom("Bearer abc123XYZ"),
            tokenHeaderName = headerNameFrom("custom-user-auth-header")
          ),
          headers = Set(headerFrom("CUSTOM-USER-AUTH-HEADER" -> "Bearer abc123XYZ"))
        )(
          blockContextAssertion = defaultOutputBlockContextAssertion(
            User.Id("userA")
          )
        )
      }
      "user is being impersonated" when {
        "impersonation is enabled" when {
          "impersonated user is on allowed users list" in {
            assertMatchRule(
              settings = TokenAuthenticationRule.Settings(
                user = User.Id("userA"),
                token = tokenFrom("Bearer abc123XYZ"),
                tokenHeaderName = headerNameFrom("custom-user-auth-header")
              ),
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
      "configured token does not correspond to the auth header one" in {
        assertNotMatchRule(
          settings = TokenAuthenticationRule.Settings(
            user = User.Id("userA"),
            token = tokenFrom("Bearer abc123XYZ"),
            tokenHeaderName = headerNameFrom("custom-user-auth-header")
          ),
          impersonation = Impersonation.Disabled,
          headers = Set(headerFrom("custom-user-auth-header" -> "Bearer 123"))
        )
      }
      "token is passed in different header than the configured one" in {
        assertNotMatchRule(
          settings = TokenAuthenticationRule.Settings(
            user = User.Id("userA"),
            token = tokenFrom("Bearer abc123XYZ"),
            tokenHeaderName = headerNameFrom("custom-user-auth-header")
          ),
          impersonation = Impersonation.Disabled,
          headers = Set(headerFrom("Authorization" -> "Bearer abc123XYZ"))
        )
      }
      "user is being impersonated" when {
        "impersonation is enabled" when {
          "admin cannot be authenticated" in {
            assertNotMatchRule(
              settings = TokenAuthenticationRule.Settings(
                user = User.Id("userA"),
                token = tokenFrom("Bearer abc123XYZ"),
                tokenHeaderName = headerNameFrom("custom-user-auth-header")
              ),
              headers = Set(basicAuthHeader("admin:wrong_pass"), impersonationHeader("userA")),
              impersonation = Impersonation.Enabled(ImpersonationSettings(
                impersonators = List(impersonatorDefFrom(
                  userIdPattern = "*",
                  impersonatorCredentials = Credentials(User.Id("admin"), PlainTextSecret("pass")),
                  impersonatedUsersIdPatterns = NonEmptyList.of("userA")
                )),
                mocksProvider = NoOpMocksProvider // not needed in this context
              )),
              rejectionCause = Some(ImpersonationNotAllowed)
            )
          }
          "admin cannot impersonate the given user" in {
            assertNotMatchRule(
              settings = TokenAuthenticationRule.Settings(
                user = User.Id("userA"),
                token = tokenFrom("Bearer abc123XYZ"),
                tokenHeaderName = headerNameFrom("custom-user-auth-header")
              ),
              headers = Set(basicAuthHeader("admin:pass"), impersonationHeader("userA")),
              impersonation = Impersonation.Enabled(ImpersonationSettings(
                impersonators = List(impersonatorDefFrom(
                  userIdPattern = "*",
                  impersonatorCredentials = Credentials(User.Id("admin"), PlainTextSecret("pass")),
                  impersonatedUsersIdPatterns = NonEmptyList.of("userB")
                )),
                mocksProvider = NoOpMocksProvider // not needed in this context
              )),
              rejectionCause = Some(ImpersonationNotAllowed)
            )
          }
          "rule doesn't accept given impersonated user" in {
            assertNotMatchRule(
              settings = TokenAuthenticationRule.Settings(
                user = User.Id("userB"),
                token = tokenFrom("Bearer abc123XYZ"),
                tokenHeaderName = headerNameFrom("custom-user-auth-header")
              ),
              headers = Set(basicAuthHeader("admin:pass"), impersonationHeader("userA")),
              impersonation = Impersonation.Enabled(ImpersonationSettings(
                impersonators = List(impersonatorDefFrom(
                  userIdPattern = "*",
                  impersonatorCredentials = Credentials(User.Id("admin"), PlainTextSecret("pass")),
                  impersonatedUsersIdPatterns = NonEmptyList.of("userA")
                )),
                mocksProvider = NoOpMocksProvider // not needed in this context
              ))
            )
          }
        }
      }

    }
  }

  private def assertMatchRule(settings: TokenAuthenticationRule.Settings,
                              impersonation: Impersonation = Impersonation.Disabled,
                              headers: Set[Header])
                             (blockContextAssertion: BlockContext => Unit): Unit =
    assertRule(settings, impersonation, headers, AssertionType.RuleFulfilled(blockContextAssertion))

  private def assertNotMatchRule(settings: TokenAuthenticationRule.Settings,
                                 impersonation: Impersonation,
                                 headers: Set[Header],
                                 rejectionCause: Option[Cause] = None): Unit =
    assertRule(settings, impersonation, headers, AssertionType.RuleRejected(rejectionCause))

  private def assertRule(settings: TokenAuthenticationRule.Settings,
                         impersonation: Impersonation,
                         headers: Set[Header],
                         assertionType: AssertionType): Unit = {
    val rule = new TokenAuthenticationRule(settings, CaseSensitivity.Enabled, impersonation)
    val requestContext = MockRequestContext.metadata.copy(headers = headers)
    val blockContext = CurrentUserMetadataRequestBlockContext(
      requestContext = requestContext,
      userMetadata = UserMetadata.from(requestContext),
      responseHeaders = Set.empty,
      responseTransformations = List.empty
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
