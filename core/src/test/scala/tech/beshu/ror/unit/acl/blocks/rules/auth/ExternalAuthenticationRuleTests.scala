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
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.{CurrentUserMetadataRequestBlockContext, GeneralNonIndexRequestBlockContext}
import tech.beshu.ror.accesscontrol.blocks.definitions.ExternalAuthenticationService
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.mocks.NoOpMocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause.{ImpersonationNotAllowed, ImpersonationNotSupported}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.ExternalAuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.ExternalAuthenticationRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.{Impersonation, ImpersonationSettings}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.{DirectlyLoggedUser, ImpersonatedUser}
import tech.beshu.ror.accesscontrol.domain.User.Id
import tech.beshu.ror.accesscontrol.domain.{CaseSensitivity, Credentials, PlainTextSecret, User}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration
import tech.beshu.ror.utils.TestsUtils.{basicAuthHeader, impersonationHeader, impersonatorDefFrom, mocksProviderForExternalAuthnServiceFrom}

import scala.concurrent.duration._
import scala.language.postfixOps

class ExternalAuthenticationRuleTests extends AnyWordSpec with MockFactory {

  "An ExternalAuthenticationRule" should {
    "match" when {
      "external authentication service returns true" in {
        val externalAuthenticationService = mockExternalAuthService(
          name = "service1",
          credentials = Credentials(User.Id("user"), PlainTextSecret("pass"))
        )

        val requestContext = MockRequestContext.indices.copy(headers = Set(basicAuthHeader("user:pass")))
        val blockContext = GeneralNonIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty)

        val rule = new ExternalAuthenticationRule(
          Settings(externalAuthenticationService),
          CaseSensitivity.Enabled,
          Impersonation.Disabled
        )
        rule.check(blockContext).runSyncStep shouldBe Right(RuleResult.Fulfilled(
          GeneralNonIndexRequestBlockContext(
            requestContext,
            UserMetadata.empty.withLoggedUser(DirectlyLoggedUser(Id("user"))),
            Set.empty,
            List.empty
          )
        ))
      }
      "user is being impersonated" when {
        "impersonation is enabled" when {
          "mocks provider has a given user for the given external service" in {
            val externalAuthenticationService = mockExternalAuthService(
              name = "service1",
              credentials = Credentials(User.Id("user"), PlainTextSecret("pass"))
            )

            val requestContext = MockRequestContext.indices.copy(
              headers = Set(basicAuthHeader("admin:pass"), impersonationHeader("user1"))
            )

            val blockContext = GeneralNonIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty)

            val rule = new ExternalAuthenticationRule(
              Settings(externalAuthenticationService),
              CaseSensitivity.Enabled,
              Impersonation.Enabled(ImpersonationSettings(
                impersonators = List(impersonatorDefFrom(
                  userIdPattern = "*",
                  impersonatorCredentials = Credentials(User.Id("admin"), PlainTextSecret("pass")),
                  impersonatedUsersIdPatterns = NonEmptyList.of("user1")
                )),
                mocksProvider = mocksProviderForExternalAuthnServiceFrom(Map(
                  ExternalAuthenticationService.Name("service1") -> Set(User.Id("user1"))
                ))
              ))
            )
            rule.check(blockContext).runSyncStep shouldBe Right(RuleResult.Fulfilled(
              GeneralNonIndexRequestBlockContext(
                requestContext,
                UserMetadata.from(requestContext).withLoggedUser(ImpersonatedUser(Id("user1"), Id("admin"))),
                Set.empty,
                List.empty
              )
            ))
          }
        }
      }
    }
    "not match" when {
      "external authentication service returns false" in {
        val externalAuthenticationService = mockExternalAuthService(
          name = "service1",
          credentials = Credentials(User.Id("user"), PlainTextSecret("pass"))
        )
        val requestContext = MockRequestContext.indices.copy(
          headers = Set(basicAuthHeader("user:wrong_pass"))
        )
        val blockContext = GeneralNonIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty)

        val rule = new ExternalAuthenticationRule(
          Settings(externalAuthenticationService),
          CaseSensitivity.Enabled,
          Impersonation.Disabled,
        )
        rule.check(blockContext).runSyncStep shouldBe Right(RuleResult.Rejected())
      }
      "user is being impersonated" when {
        "impersonation is enabled" when {
          "admin cannot be authenticated" in {
            val requestContext = MockRequestContext.indices.copy(
              headers = Set(basicAuthHeader("admin:pass"), impersonationHeader("user1"))
            )
            val blockContext = CurrentUserMetadataRequestBlockContext(requestContext, UserMetadata.from(requestContext), Set.empty, List.empty)

            val rule = new ExternalAuthenticationRule(
              Settings(mock[ExternalAuthenticationService]),
              CaseSensitivity.Enabled,
              Impersonation.Enabled(ImpersonationSettings(
                impersonators = List(impersonatorDefFrom(
                  userIdPattern = "*",
                  impersonatorCredentials = Credentials(User.Id("admin"), PlainTextSecret("different_password")),
                  impersonatedUsersIdPatterns = NonEmptyList.of("user1")
                )),
                mocksProvider = mocksProviderForExternalAuthnServiceFrom(Map(
                  ExternalAuthenticationService.Name("service1") -> Set(User.Id("user1"))
                ))
              ))
            )

            rule.check(blockContext).runSyncStep shouldBe Right(RuleResult.Rejected(ImpersonationNotAllowed))
          }
          "admin cannot impersonate the given user" in {
            val requestContext = MockRequestContext.indices.copy(
              headers = Set(basicAuthHeader("admin:pass"), impersonationHeader("user1"))
            )
            val blockContext = CurrentUserMetadataRequestBlockContext(requestContext, UserMetadata.from(requestContext), Set.empty, List.empty)

            val rule = new ExternalAuthenticationRule(
              Settings(mock[ExternalAuthenticationService]),
              CaseSensitivity.Enabled,
              Impersonation.Enabled(ImpersonationSettings(
                impersonators = List(impersonatorDefFrom(
                  userIdPattern = "*",
                  impersonatorCredentials = Credentials(User.Id("admin"), PlainTextSecret("pass")),
                  impersonatedUsersIdPatterns = NonEmptyList.of("user2")
                )),
                mocksProvider = mocksProviderForExternalAuthnServiceFrom(Map(
                  ExternalAuthenticationService.Name("service1") -> Set(User.Id("user1"))
                ))
              ))
            )

            rule.check(blockContext).runSyncStep shouldBe Right(RuleResult.Rejected(ImpersonationNotAllowed))
          }
          "mocks provider doesn't have the given user" in {
            val externalAuthenticationService = mockExternalAuthService(
              name = "service1",
              credentials = Credentials(User.Id("user"), PlainTextSecret("pass"))
            )

            val requestContext = MockRequestContext.indices.copy(
              headers = Set(basicAuthHeader("admin:pass"), impersonationHeader("user1"))
            )
            val blockContext = CurrentUserMetadataRequestBlockContext(requestContext, UserMetadata.from(requestContext), Set.empty, List.empty)

            val rule = new ExternalAuthenticationRule(
              Settings(externalAuthenticationService),
              CaseSensitivity.Enabled,
              Impersonation.Enabled(ImpersonationSettings(
                impersonators = List(impersonatorDefFrom(
                  userIdPattern = "*",
                  impersonatorCredentials = Credentials(User.Id("admin"), PlainTextSecret("pass")),
                  impersonatedUsersIdPatterns = NonEmptyList.of("user1")
                )),
                mocksProvider = mocksProviderForExternalAuthnServiceFrom(Map(
                  ExternalAuthenticationService.Name("service1") -> Set(User.Id("user2"))
                ))
              ))
            )

            rule.check(blockContext).runSyncStep shouldBe Right(RuleResult.Rejected())
          }
          "mocks provider is unavailable" in {
            val externalAuthenticationService = mockExternalAuthService(
              name = "service1",
              credentials = Credentials(User.Id("user"), PlainTextSecret("pass"))
            )

            val requestContext = MockRequestContext.indices.copy(
              headers = Set(basicAuthHeader("admin:pass"), impersonationHeader("user1"))
            )
            val blockContext = CurrentUserMetadataRequestBlockContext(requestContext, UserMetadata.from(requestContext), Set.empty, List.empty)

            val rule = new ExternalAuthenticationRule(
              Settings(externalAuthenticationService),
              CaseSensitivity.Enabled,
              Impersonation.Enabled(ImpersonationSettings(
                impersonators = List(impersonatorDefFrom(
                  userIdPattern = "*",
                  impersonatorCredentials = Credentials(User.Id("admin"), PlainTextSecret("pass")),
                  impersonatedUsersIdPatterns = NonEmptyList.of("user1")
                )),
                mocksProvider = NoOpMocksProvider
              ))
            )

            rule.check(blockContext).runSyncStep shouldBe Right(RuleResult.Rejected(ImpersonationNotSupported))
          }
        }
        "impersonation is disabled" when {
          "admin is trying to impersonate user" in {
            val externalAuthenticationService = mockExternalAuthService(
              name = "service1",
              credentials = Credentials(User.Id("user"), PlainTextSecret("pass"))
            )

            val requestContext = MockRequestContext.indices.copy(
              headers = Set(basicAuthHeader("admin:pass"), impersonationHeader("user1"))
            )
            val blockContext = CurrentUserMetadataRequestBlockContext(requestContext, UserMetadata.from(requestContext), Set.empty, List.empty)

            val rule = new ExternalAuthenticationRule(
              Settings(externalAuthenticationService),
              CaseSensitivity.Enabled,
              Impersonation.Disabled
            )

            rule.check(blockContext).runSyncStep shouldBe Right(RuleResult.Rejected())
          }
        }
      }
    }
  }

  private def mockExternalAuthService(name: NonEmptyString, credentials: Credentials) = {
    new ExternalAuthenticationService {
      override def id: ExternalAuthenticationService.Name = ExternalAuthenticationService.Name(name)

      override def authenticate(aCredentials: Credentials): Task[Boolean] = Task.delay {
        credentials == aCredentials
      }

      override def serviceTimeout: PositiveFiniteDuration = Refined.unsafeApply(5 second)
    }
  }

}
