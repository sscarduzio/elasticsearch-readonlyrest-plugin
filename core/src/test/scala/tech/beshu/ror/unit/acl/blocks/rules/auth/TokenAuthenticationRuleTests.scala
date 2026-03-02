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
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext}
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause.{AuthenticationFailed, ImpersonationNotAllowed}
import tech.beshu.ror.accesscontrol.blocks.BlockContext.UserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.BlockMetadata
import tech.beshu.ror.accesscontrol.blocks.mocks.NoOpMocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.auth.TokenAuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.TokenAuthenticationRule.Settings.TokenType.{ApiKey, ServiceToken, StaticToken}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.{Impersonation, ImpersonationSettings}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.AuthorizationTokenDef.AllowedPrefix.StrictlyDefined
import tech.beshu.ror.accesscontrol.domain.AuthorizationTokenPrefix.{api, bearer}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.{DirectlyLoggedUser, ImpersonatedUser}
import tech.beshu.ror.es.EsServices
import tech.beshu.ror.mocks.MockEsServices.{MockApiKeyService, MockServiceAccountTokenService}
import tech.beshu.ror.mocks.{MockEsServices, MockRequestContext}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.*

import scala.language.postfixOps

class TokenAuthenticationRuleTests
  extends AnyWordSpec with BlockContextAssertion {

  "A TokenAuthenticationRule" should {
    "match" when {
      "static token is configured and default header name is used" in {
        assertMatchRule(
          settings = TokenAuthenticationRule.Settings(
            user = User.Id("userA"),
            tokenType = StaticToken(strictlyDefinedBearerTokenDef, authorizationTokenFrom("Bearer abc123XYZ"))
          ),
          headers = Set(headerFrom("Authorization" -> "Bearer abc123XYZ"))
        )(
          blockContextAssertion = defaultOutputBlockContextAssertion(
            User.Id("userA")
          )
        )
      }
      "static token and header name are configured and the same token can be find in auth header" in {
        assertMatchRule(
          settings = TokenAuthenticationRule.Settings(
            user = User.Id("userA"),
            tokenType = StaticToken(
              AuthorizationTokenDef(headerNameFrom("custom-user-auth-header"), StrictlyDefined(bearer)),
              authorizationTokenFrom("Bearer abc123XYZ")
            )
          ),
          headers = Set(headerFrom("custom-user-auth-header" -> "Bearer abc123XYZ"))
        )(
          blockContextAssertion = defaultOutputBlockContextAssertion(
            User.Id("userA")
          )
        )
      }
      "static token and header name are configured and the same token can be find in auth header (header name case ignoring)" in {
        assertMatchRule(
          settings = TokenAuthenticationRule.Settings(
            user = User.Id("userA"),
            tokenType = StaticToken(
              AuthorizationTokenDef(headerNameFrom("custom-user-auth-header"), StrictlyDefined(bearer)),
              authorizationTokenFrom("Bearer abc123XYZ")
            )
          ),
          headers = Set(headerFrom("CUSTOM-USER-AUTH-HEADER" -> "Bearer abc123XYZ"))
        )(
          blockContextAssertion = defaultOutputBlockContextAssertion(
            User.Id("userA")
          )
        )
      }
      "service-token type is configured and valid token is in the default header" in {
        assertMatchRule(
          settings = TokenAuthenticationRule.Settings(
            user = User.Id("svc-user"),
            tokenType = ServiceToken(strictlyDefinedBearerTokenDef)
          ),
          headers = Set(headerFrom("Authorization" -> "Bearer svc-token")),
          esServices = MockEsServices.`with`(new MockServiceAccountTokenService(true))
        )(
          blockContextAssertion = defaultOutputBlockContextAssertion(User.Id("svc-user"))
        )
      }
      "service-token type is configured and valid token is in a custom header" in {
        assertMatchRule(
          settings = TokenAuthenticationRule.Settings(
            user = User.Id("svc-user"),
            tokenType = ServiceToken(
              AuthorizationTokenDef(headerNameFrom("X-Service-Token"), StrictlyDefined(bearer))
            )
          ),
          headers = Set(headerFrom("X-Service-Token" -> "Bearer svc-token")),
          esServices = MockEsServices.`with`(new MockServiceAccountTokenService(true))
        )(
          blockContextAssertion = defaultOutputBlockContextAssertion(User.Id("svc-user"))
        )
      }
      "api-key type is configured and valid token is in the default header" in {
        assertMatchRule(
          settings = TokenAuthenticationRule.Settings(
            user = User.Id("api-user"),
            tokenType = ApiKey(apiKeyDef)
          ),
          headers = Set(headerFrom("Authorization" -> "APIKey api-key-value")),
          esServices = MockEsServices.`with`(new MockApiKeyService(true))
        )(
          blockContextAssertion = defaultOutputBlockContextAssertion(User.Id("api-user"))
        )
      }
      "api-key type is configured and valid token is in a custom header" in {
        assertMatchRule(
          settings = TokenAuthenticationRule.Settings(
            user = User.Id("api-user"),
            tokenType = ApiKey(
              AuthorizationTokenDef(headerNameFrom("X-Api-Key"), StrictlyDefined(api))
            )
          ),
          headers = Set(headerFrom("X-Api-Key" -> "ApiKey api-key-value")),
          esServices = MockEsServices.`with`(new MockApiKeyService(true))
        )(
          blockContextAssertion = defaultOutputBlockContextAssertion(User.Id("api-user"))
        )
      }
      "user is being impersonated" when {
        "impersonation is enabled" when {
          "impersonated user is on allowed users list" in {
            assertMatchRule(
              settings = TokenAuthenticationRule.Settings(
                user = User.Id("userA"),
                tokenType = StaticToken(
                  AuthorizationTokenDef(headerNameFrom("custom-user-auth-header"), StrictlyDefined(bearer)),
                  authorizationTokenFrom("Bearer abc123XYZ")
                )
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
      "configured static token does not correspond to the auth header one" in {
        assertNotMatchRule(
          settings = TokenAuthenticationRule.Settings(
            user = User.Id("userA"),
            tokenType = StaticToken(
              AuthorizationTokenDef(headerNameFrom("custom-user-auth-header"), StrictlyDefined(bearer)),
              authorizationTokenFrom("Bearer abc123XYZ")
            )
          ),
          impersonation = Impersonation.Disabled,
          headers = Set(headerFrom("custom-user-auth-header" -> "Bearer 123")),
          denialCause = AuthenticationFailed("Token header 'custom-user-auth-header' missing or invalid")
        )
      }
      "static token is passed in different header than the configured one" in {
        assertNotMatchRule(
          settings = TokenAuthenticationRule.Settings(
            user = User.Id("userA"),
            tokenType = StaticToken(
              AuthorizationTokenDef(headerNameFrom("custom-user-auth-header"), StrictlyDefined(bearer)),
              authorizationTokenFrom("Bearer abc123XYZ")
            )
          ),
          impersonation = Impersonation.Disabled,
          headers = Set(headerFrom("Authorization" -> "Bearer abc123XYZ")),
          denialCause = AuthenticationFailed("Token header 'custom-user-auth-header' missing or invalid")
        )
      }
      "service-token validation fails" in {
        assertNotMatchRule(
          settings = TokenAuthenticationRule.Settings(
            user = User.Id("svc-user"),
            tokenType = ServiceToken(strictlyDefinedBearerTokenDef)
          ),
          impersonation = Impersonation.Disabled,
          headers = Set(headerFrom("Authorization" -> "Bearer svc-token")),
          esServices = MockEsServices.`with`(new MockServiceAccountTokenService(false)),
          denialCause = AuthenticationFailed("Token header 'Authorization' missing or invalid")
        )
      }
      "service-token header is absent" in {
        assertNotMatchRule(
          settings = TokenAuthenticationRule.Settings(
            user = User.Id("svc-user"),
            tokenType = ServiceToken(strictlyDefinedBearerTokenDef)
          ),
          impersonation = Impersonation.Disabled,
          headers = Set.empty,
          denialCause = AuthenticationFailed("Token header 'Authorization' missing or invalid")
        )
      }
      "service-token header has wrong prefix" in {
        assertNotMatchRule(
          settings = TokenAuthenticationRule.Settings(
            user = User.Id("svc-user"),
            tokenType = ServiceToken(strictlyDefinedBearerTokenDef)
          ),
          impersonation = Impersonation.Disabled,
          headers = Set(headerFrom("Authorization" -> "Api svc-token")),
          denialCause = AuthenticationFailed("Token header 'Authorization' missing or invalid")
        )
      }
      "api-key validation fails" in {
        assertNotMatchRule(
          settings = TokenAuthenticationRule.Settings(
            user = User.Id("api-user"),
            tokenType = ApiKey(apiKeyDef)
          ),
          impersonation = Impersonation.Disabled,
          headers = Set(headerFrom("Authorization" -> "Api api-key-value")),
          esServices = MockEsServices.`with`(new MockApiKeyService(false)),
          denialCause = AuthenticationFailed("Token header 'Authorization' missing or invalid")
        )
      }
      "api-key header is absent" in {
        assertNotMatchRule(
          settings = TokenAuthenticationRule.Settings(
            user = User.Id("api-user"),
            tokenType = ApiKey(apiKeyDef)
          ),
          impersonation = Impersonation.Disabled,
          headers = Set.empty,
          denialCause = AuthenticationFailed("Token header 'Authorization' missing or invalid")
        )
      }
      "api-key header has wrong prefix" in {
        assertNotMatchRule(
          settings = TokenAuthenticationRule.Settings(
            user = User.Id("api-user"),
            tokenType = ApiKey(apiKeyDef)
          ),
          impersonation = Impersonation.Disabled,
          headers = Set(headerFrom("Authorization" -> "Bearer api-key-value")),
          denialCause = AuthenticationFailed("Token header 'Authorization' missing or invalid")
        )
      }
      "user is being impersonated" when {
        "impersonation is enabled" when {
          "admin cannot be authenticated" in {
            assertNotMatchRule(
              settings = TokenAuthenticationRule.Settings(
                user = User.Id("userA"),
                tokenType = StaticToken(
                  AuthorizationTokenDef(headerNameFrom("custom-user-auth-header"), StrictlyDefined(bearer)),
                  authorizationTokenFrom("Bearer abc123XYZ")
                )
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
              denialCause = ImpersonationNotAllowed
            )
          }
          "admin cannot impersonate the given user" in {
            assertNotMatchRule(
              settings = TokenAuthenticationRule.Settings(
                user = User.Id("userA"),
                tokenType = StaticToken(
                  AuthorizationTokenDef(headerNameFrom("custom-user-auth-header"), StrictlyDefined(bearer)),
                  authorizationTokenFrom("Bearer abc123XYZ")
                )
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
              denialCause = ImpersonationNotAllowed
            )
          }
          "rule doesn't accept given impersonated user" in {
            assertNotMatchRule(
              settings = TokenAuthenticationRule.Settings(
                user = User.Id("userB"),
                tokenType = StaticToken(
                  AuthorizationTokenDef(headerNameFrom("custom-user-auth-header"), StrictlyDefined(bearer)),
                  authorizationTokenFrom("Bearer abc123XYZ")
                )
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
              denialCause = AuthenticationFailed("Impersonated user does not exist")
            )
          }
        }
      }
    }
  }

  private def assertMatchRule(settings: TokenAuthenticationRule.Settings,
                              impersonation: Impersonation = Impersonation.Disabled,
                              headers: Set[Header],
                              esServices: EsServices = MockEsServices.dummy)
                             (blockContextAssertion: BlockContext => Unit): Unit =
    assertRule(settings, impersonation, headers, esServices, RuleCheckAssertion.RulePermitted(blockContextAssertion))

  private def assertNotMatchRule(settings: TokenAuthenticationRule.Settings,
                                 impersonation: Impersonation,
                                 headers: Set[Header],
                                 denialCause: Cause,
                                 esServices: EsServices = MockEsServices.dummy): Unit =
    assertRule(settings, impersonation, headers, esServices, RuleCheckAssertion.RuleDenied(denialCause))

  private def assertRule(settings: TokenAuthenticationRule.Settings,
                         impersonation: Impersonation,
                         headers: Set[Header],
                         esServices: EsServices,
                         assertion: RuleCheckAssertion): Unit = {
    val rule = new TokenAuthenticationRule(settings, CaseSensitivity.Enabled, impersonation)
    val requestContext = MockRequestContext.indices.withHeaders(headers).withEsServices(esServices)
    val blockContext = UserMetadataRequestBlockContext(
      block = mock[Block],
      requestContext = requestContext,
      blockMetadata = BlockMetadata.from(requestContext),
      responseHeaders = Set.empty,
      responseTransformations = List.empty
    )
    rule.checkAndAssert(blockContext, assertion)
  }

  private def defaultOutputBlockContextAssertion(user: User.Id): BlockContext => Unit =
    (blockContext: BlockContext) => {
      assertBlockContext(blockContext)(
        loggedUser = Some(DirectlyLoggedUser(user))
      )
    }

  private def impersonatedUserOutputBlockContextAssertion(user: User.Id,
                                                          impersonator: User.Id): BlockContext => Unit =
    (blockContext: BlockContext) => {
      assertBlockContext(blockContext)(
        loggedUser = Some(ImpersonatedUser(user, impersonator))
      )
    }
}
