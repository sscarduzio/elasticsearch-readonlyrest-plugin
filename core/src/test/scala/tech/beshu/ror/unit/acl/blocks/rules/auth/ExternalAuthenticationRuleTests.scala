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
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.BlockContext.{GeneralNonIndexRequestBlockContext, UserMetadataRequestBlockContext}
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause.{AuthenticationFailed, ImpersonationNotAllowed, ImpersonationNotSupported}
import tech.beshu.ror.accesscontrol.blocks.Decision.{Denied, Permitted}
import tech.beshu.ror.accesscontrol.blocks.definitions.ExternalAuthenticationService
import tech.beshu.ror.accesscontrol.blocks.definitions.ExternalAuthenticationService.AuthenticationResult
import tech.beshu.ror.accesscontrol.blocks.metadata.BlockMetadata
import tech.beshu.ror.accesscontrol.blocks.mocks.NoOpMocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.auth.ExternalAuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.ExternalAuthenticationRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.{Impersonation, ImpersonationSettings}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.LoggedUser.{DirectlyLoggedUser, ImpersonatedUser}
import tech.beshu.ror.accesscontrol.domain.User.Id
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration
import tech.beshu.ror.utils.TestsUtils.*

import scala.concurrent.duration.*
import scala.language.postfixOps

class ExternalAuthenticationRuleTests extends AnyWordSpec with Inside with BlockContextAssertion {

  "An ExternalAuthenticationRule" should {
    "match" when {
      "external authentication service returns true" in {
        val externalAuthenticationService = mockExternalAuthService(
          name = "service1",
          credentials = Credentials(User.Id("user"), PlainTextSecret("pass"))
        )

        val requestContext = MockRequestContext.indices.withHeaders(basicAuthHeader("user:pass"))
        val blockContext = GeneralNonIndexRequestBlockContext(mock[Block], requestContext, BlockMetadata.empty, Set.empty, List.empty)

        val rule = new ExternalAuthenticationRule(
          Settings(externalAuthenticationService),
          CaseSensitivity.Enabled,
          Impersonation.Disabled
        )
        val result = rule.check(blockContext).runSyncUnsafe()

        inside(result) {
          case Permitted(blockContext) =>
            assertBlockContext(blockContext)(
              loggedUser = Some(DirectlyLoggedUser(Id("user")))
            )
        }
      }
      "user is being impersonated" when {
        "impersonation is enabled" when {
          "mocks provider has a given user for the given external service" in {
            val externalAuthenticationService = mockExternalAuthService(
              name = "service1",
              credentials = Credentials(User.Id("user"), PlainTextSecret("pass"))
            )
            val requestContext = MockRequestContext.indices.withHeaders(
              basicAuthHeader("admin:pass"), impersonationHeader("user1")
            )

            val blockContext = GeneralNonIndexRequestBlockContext(mock[Block], requestContext, BlockMetadata.empty, Set.empty, List.empty)

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

            val result = rule.check(blockContext).runSyncUnsafe()

            inside(result) {
              case Permitted(blockContext) =>
                assertBlockContext(blockContext)(
                  loggedUser = Some(ImpersonatedUser(Id("user1"), Id("admin")))
                )
            }
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
        val requestContext = MockRequestContext.indices.withHeaders(basicAuthHeader("user:wrong_pass"))
        val blockContext = GeneralNonIndexRequestBlockContext(mock[Block], requestContext, BlockMetadata.empty, Set.empty, List.empty)

        val rule = new ExternalAuthenticationRule(
          Settings(externalAuthenticationService),
          CaseSensitivity.Enabled,
          Impersonation.Disabled,
        )

        val result = rule.check(blockContext).runSyncUnsafe()

        result should be (Denied(AuthenticationFailed("mock - auth failed")))
      }
      "user is being impersonated" when {
        "impersonation is enabled" when {
          "admin cannot be authenticated" in {
            val requestContext = MockRequestContext.indices.withHeaders(
              basicAuthHeader("admin:pass"), impersonationHeader("user1")
            )
            val blockContext = UserMetadataRequestBlockContext(mock[Block], requestContext, BlockMetadata.from(requestContext), Set.empty, List.empty)

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

            rule.check(blockContext).runSyncStep shouldBe Right(Denied(ImpersonationNotAllowed))
          }
          "admin cannot impersonate the given user" in {
            val requestContext = MockRequestContext.indices.withHeaders(
              basicAuthHeader("admin:pass"), impersonationHeader("user1")
            )
            val blockContext = UserMetadataRequestBlockContext(mock[Block], requestContext, BlockMetadata.from(requestContext), Set.empty, List.empty)

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

            val result = rule.check(blockContext).runSyncUnsafe()

            result should be(Denied(ImpersonationNotAllowed))
          }
          "mocks provider doesn't have the given user" in {
            val externalAuthenticationService = mockExternalAuthService(
              name = "service1",
              credentials = Credentials(User.Id("user"), PlainTextSecret("pass"))
            )

            val requestContext = MockRequestContext.indices.withHeaders(
              basicAuthHeader("admin:pass"), impersonationHeader("user1")
            )
            val blockContext = UserMetadataRequestBlockContext(mock[Block], requestContext, BlockMetadata.from(requestContext), Set.empty, List.empty)

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

            val result = rule.check(blockContext).runSyncUnsafe()

            result should be(Denied(AuthenticationFailed("Impersonated user does not exist")))
          }
          "mocks provider is unavailable" in {
            val externalAuthenticationService = mockExternalAuthService(
              name = "service1",
              credentials = Credentials(User.Id("user"), PlainTextSecret("pass"))
            )

            val requestContext = MockRequestContext.indices.withHeaders(
              basicAuthHeader("admin:pass"), impersonationHeader("user1")
            )
            val blockContext = UserMetadataRequestBlockContext(mock[Block], requestContext, BlockMetadata.from(requestContext), Set.empty, List.empty)

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

            val result = rule.check(blockContext).runSyncUnsafe()

            result should be(Denied(ImpersonationNotSupported))
          }
        }
        "impersonation is disabled" when {
          "admin is trying to impersonate user" in {
            val externalAuthenticationService = mockExternalAuthService(
              name = "service1",
              credentials = Credentials(User.Id("user"), PlainTextSecret("pass"))
            )

            val requestContext = MockRequestContext.indices.withHeaders(
              basicAuthHeader("admin:pass"), impersonationHeader("user1")
            )
            val blockContext = UserMetadataRequestBlockContext(mock[Block], requestContext, BlockMetadata.from(requestContext), Set.empty, List.empty)

            val rule = new ExternalAuthenticationRule(
              Settings(externalAuthenticationService),
              CaseSensitivity.Enabled,
              Impersonation.Disabled
            )

            val result = rule.check(blockContext).runSyncUnsafe()

            result should be(Denied(AuthenticationFailed("mock - auth failed")))
          }
        }
      }
    }
  }

  private def mockExternalAuthService(name: NonEmptyString, credentials: Credentials) = {
    new ExternalAuthenticationService {
      override def id: ExternalAuthenticationService.Name = ExternalAuthenticationService.Name(name)

      override def authenticate(aCredentials: Credentials)
                               (implicit requestId: RequestId): Task[AuthenticationResult] = Task.delay {
        if(credentials == aCredentials) Right(DirectlyLoggedUser(credentials.user))
        else Left(AuthenticationFailed("mock - auth failed"))
      }

      override def serviceTimeout: PositiveFiniteDuration = Refined.unsafeApply(5 second)
    }
  }

}
