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
import tech.beshu.ror.accesscontrol.domain.User
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason.MalformedValue
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.providers.EnvVarProvider.EnvVarName
import tech.beshu.ror.providers.EnvVarsProvider
import tech.beshu.ror.unit.acl.factory.decoders.rules.BaseRuleSettingsDecoderTest
import tech.beshu.ror.utils.TestsUtils.{headerNameFrom, tokenFrom, unsafeNes}

class TokenAuthenticationRuleSettingsTests
  extends BaseRuleSettingsDecoderTest[TokenAuthenticationRule] {

  "A TokenAuthenticationRule" should {
    "be able to be loaded from config" when {
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
              token = tokenFrom("Bearer abc123XYZ"),
              tokenHeaderName = headerNameFrom("Authorization")
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
              token = tokenFrom("Bearer abc123XYZ"),
              tokenHeaderName = headerNameFrom("X-Custom-Header")
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
              token = tokenFrom("Bearer abc123XYZ"),
              tokenHeaderName = headerNameFrom("Authorization")
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
              token = tokenFrom("Bearer abc123XYZ"),
              tokenHeaderName = headerNameFrom("Authorization")
            ))
        )
      }
    }
    "not be able to be loaded from config" when {
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
            errors.head should be(RulesLevelCreationError(MalformedValue.fromString(
              """token_authentication:
                |  username: "john"
                |""".stripMargin
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
