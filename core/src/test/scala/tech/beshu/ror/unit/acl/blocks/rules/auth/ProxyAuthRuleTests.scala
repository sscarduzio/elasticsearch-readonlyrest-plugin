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
import tech.beshu.ror.accesscontrol.blocks.rules.auth.ProxyAuthRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.{Impersonation, ImpersonationSettings}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.LoggedUser.{DirectlyLoggedUser, ImpersonatedUser}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.concurrent.duration.*
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

class ProxyAuthRuleTests extends AnyWordSpec with Inside with BlockContextAssertion {

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
          headers = Set(headerFrom("custom-user-auth-header" -> "userD"))
        )
      }
      "user id is passed in different header than the configured one" in {
        assertNotMatchRule(
          settings = ProxyAuthRule.Settings(
            UniqueNonEmptyList.of(User.Id("userA")),
            headerNameFrom("custom-user-auth-header")
          ),
          headers = Set(headerFrom("X-Forwarded-User" -> "userD"))
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
              rejectionCause = Some(ImpersonationNotAllowed)
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
              rejectionCause = Some(ImpersonationNotAllowed)
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
              ))
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
    assertRule(settings, impersonation, headers, AssertionType.RuleFulfilled(blockContextAssertion))

  private def assertNotMatchRule(settings: ProxyAuthRule.Settings,
                                 impersonation: Impersonation = Impersonation.Disabled,
                                 headers: Set[Header],
                                 rejectionCause: Option[Cause] = None): Unit =
    assertRule(settings, impersonation, headers, AssertionType.RuleRejected(rejectionCause))

  private def assertRule(settings: ProxyAuthRule.Settings,
                         impersonation: Impersonation,
                         headers: Set[Header],
                         assertionType: AssertionType): Unit = {
    val rule = new ProxyAuthRule(settings, CaseSensitivity.Enabled, impersonation)
    val requestContext = MockRequestContext.metadata.copy(headers = headers)
    val blockContext = CurrentUserMetadataRequestBlockContext(
      requestContext,
      UserMetadata.from(requestContext),
      Set.empty,
      List.empty
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
