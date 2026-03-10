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
package tech.beshu.ror.unit.acl.factory.decoders.rules.auth

import org.scalatest.matchers.should.Matchers.*
import tech.beshu.ror.accesscontrol.blocks.rules.auth.TokenAuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.TokenAuthenticationRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.auth.TokenAuthenticationRule.Settings.TokenType.{ApiKey, ServiceToken, StaticToken}
import tech.beshu.ror.accesscontrol.domain.AuthorizationTokenDef.AllowedPrefix
import tech.beshu.ror.accesscontrol.domain.AuthorizationTokenDef.AllowedPrefix.StrictlyDefined
import tech.beshu.ror.accesscontrol.domain.AuthorizationTokenPrefix.{api, bearer}
import tech.beshu.ror.accesscontrol.domain.{AuthorizationTokenDef, User}
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.providers.EnvVarProvider.EnvVarName
import tech.beshu.ror.providers.EnvVarsProvider
import tech.beshu.ror.unit.acl.factory.decoders.rules.BaseRuleSettingsDecoderTest
import tech.beshu.ror.utils.TestsUtils.{anyTokenDef, authorizationTokenFrom, headerNameFrom, unsafeNes}

class TokenAuthenticationRuleSettingsTests
  extends BaseRuleSettingsDecoderTest[TokenAuthenticationRule] {

  "A TokenAuthenticationRule" should {
    "be able to be loaded from settings" when {
      "token and username defined" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    token_authentication:
               |      token: "Bearer abc123XYZ"
               |      username: "john"
               |""".stripMargin,
          assertion = rule =>
            rule.settings should be(Settings(
              user = User.Id("john"),
              tokenType = StaticToken(anyTokenDef, authorizationTokenFrom("Bearer abc123XYZ"))
            ))
        )
      }
      "token, username and custom header defined" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    token_authentication:
               |      token: "Bearer abc123XYZ"
               |      username: "john"
               |      header: "X-Custom-Header"
               |""".stripMargin,
          assertion = rule =>
            rule.settings should be(Settings(
              user = User.Id("john"),
              tokenType = StaticToken(
                AuthorizationTokenDef(headerNameFrom("X-Custom-Header"), AllowedPrefix.Any),
                authorizationTokenFrom("Bearer abc123XYZ")
              )
            ))
        )
      }
      "token, username and authorization header defined" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    token_authentication:
               |      token: "Bearer abc123XYZ"
               |      username: "john"
               |      header: "Authorization"
               |""".stripMargin,
          assertion = rule =>
            rule.settings should be(Settings(
              user = User.Id("john"),
              tokenType = StaticToken(anyTokenDef, authorizationTokenFrom("Bearer abc123XYZ"))
            ))
        )
      }
      "token uses env variable" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    token_authentication:
               |      token: "Bearer @{env:SECRET_TOKEN}"
               |      username: "john"
               |""".stripMargin,
          assertion = rule =>
            rule.settings should be(Settings(
              user = User.Id("john"),
              tokenType = StaticToken(anyTokenDef, authorizationTokenFrom("Bearer abc123XYZ"))
            ))
        )
      }
      "type is explicitly set to 'static'" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    token_authentication:
               |      type: "static"
               |      token: "Bearer abc123XYZ"
               |      username: "john"
               |""".stripMargin,
          assertion = rule =>
            rule.settings should be(Settings(
              user = User.Id("john"),
              tokenType = StaticToken(anyTokenDef, authorizationTokenFrom("Bearer abc123XYZ"))
            ))
        )
      }
      "type is 'service-token' with default header" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    token_authentication:
               |      type: "service-token"
               |      username: "svc-account"
               |""".stripMargin,
          assertion = rule =>
            rule.settings should be(Settings(
              user = User.Id("svc-account"),
              tokenType = ServiceToken(
                AuthorizationTokenDef(headerNameFrom("Authorization"), StrictlyDefined(bearer))
              )
            ))
        )
      }
      "type is 'service-token' with custom header" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    token_authentication:
               |      type: "service-token"
               |      username: "svc-account"
               |      header: "X-Service-Token"
               |""".stripMargin,
          assertion = rule =>
            rule.settings should be(Settings(
              user = User.Id("svc-account"),
              tokenType = ServiceToken(
                AuthorizationTokenDef(headerNameFrom("X-Service-Token"), StrictlyDefined(bearer))
              )
            ))
        )
      }
      "type is 'api-key' with default header" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    token_authentication:
               |      type: "api-key"
               |      username: "api-user"
               |""".stripMargin,
          assertion = rule =>
            rule.settings should be(Settings(
              user = User.Id("api-user"),
              tokenType = ApiKey(
                AuthorizationTokenDef(headerNameFrom("Authorization"), StrictlyDefined(api))
              )
            ))
        )
      }
      "type is 'api-key' with custom header" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    token_authentication:
               |      type: "api-key"
               |      username: "api-user"
               |      header: "X-Api-Key"
               |""".stripMargin,
          assertion = rule =>
            rule.settings should be(Settings(
              user = User.Id("api-user"),
              tokenType = ApiKey(
                AuthorizationTokenDef(headerNameFrom("X-Api-Key"), StrictlyDefined(api))
              )
            ))
        )
      }
    }
    "not be able to be loaded from settings" when {
      "username is not defined" in {
        assertDecodingFailure(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    token_authentication:
               |      token: "Bearer abc123XYZ"
               |""".stripMargin,
          assertion = { errors =>
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue.fromString(
              """token_authentication:
                |  token: "Bearer abc123XYZ"
                |""".stripMargin
            )))
          }
        )
      }
      "token is not defined" in {
        assertDecodingFailure(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    token_authentication:
               |      username: "john"
               |""".stripMargin,
          assertion = { errors =>
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message(
              "Static token type requires the 'token' field. See: https://docs.readonlyrest.com/elasticsearch#token_authentication"
            )))
          }
        )
      }
      "type is 'service-token' but a static token value is also provided" in {
        assertDecodingFailure(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    token_authentication:
               |      type: "service-token"
               |      token: "Bearer abc123XYZ"
               |      username: "svc-account"
               |""".stripMargin,
          assertion = { errors =>
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message(
              "You cannot define static 'token' value when token type is 'service-token'. See: https://docs.readonlyrest.com/elasticsearch#token_authentication"
            )))
          }
        )
      }
      "type is 'api-key' but a static token value is also provided" in {
        assertDecodingFailure(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    token_authentication:
               |      type: "api-key"
               |      token: "Api abc123XYZ"
               |      username: "api-user"
               |""".stripMargin,
          assertion = { errors =>
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message(
              "You cannot define static 'token' value when token type is 'api-key'. See: https://docs.readonlyrest.com/elasticsearch#token_authentication"
            )))
          }
        )
      }
      "type is unknown" in {
        assertDecodingFailure(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    token_authentication:
               |      type: "oauth2"
               |      username: "john"
               |""".stripMargin,
          assertion = { errors =>
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message(
              "Unknown token type 'oauth2'. See: https://docs.readonlyrest.com/elasticsearch#token_authentication"
            )))
          }
        )
      }
    }
  }

  override implicit protected def envVarsProvider: EnvVarsProvider = {
    case EnvVarName(env) if env.value == "SECRET_TOKEN" =>
      Some("abc123XYZ")
    case _ =>
      None
  }
}
