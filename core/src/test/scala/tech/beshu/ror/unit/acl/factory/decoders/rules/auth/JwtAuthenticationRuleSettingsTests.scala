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

import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers.*
import tech.beshu.ror.accesscontrol.blocks.definitions.JwtDef.SignatureCheckMethod
import tech.beshu.ror.accesscontrol.blocks.definitions.{CacheableExternalAuthenticationServiceDecorator, JwtDef}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.JwtAuthenticationRule
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory.HttpClient
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.{DefinitionsLevelCreationError, GeneralReadonlyrestSettingsError, RulesLevelCreationError}
import tech.beshu.ror.mocks.MockHttpClientsFactoryWithFixedHttpClient
import tech.beshu.ror.providers.EnvVarProvider.EnvVarName
import tech.beshu.ror.providers.EnvVarsProvider
import tech.beshu.ror.unit.acl.factory.decoders.rules.BaseRuleSettingsDecoderTest
import tech.beshu.ror.utils.TestsUtils.*

import java.security.KeyPairGenerator
import java.util.Base64

class JwtAuthenticationRuleSettingsTests
  extends BaseRuleSettingsDecoderTest[JwtAuthenticationRule]
    with MockFactory {

  "A JwtAuthenticationRule" should {
    "be able to be loaded from config" when {
      "rule is defined using simplified version and minimal required set of fields in JWT definition" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    jwt_authentication: jwt1
              |
              |  jwt:
              |
              |  - name: jwt1
              |    user_claim: user
              |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.jwt.id should be(JwtDef.Name("jwt1"))
            rule.settings.jwt.authorizationTokenDef should be(AuthorizationTokenDef(Header.Name.authorization, "Bearer "))
            rule.settings.jwt.checkMethod shouldBe a [SignatureCheckMethod.Hmac]
            rule.settings.jwt.userClaim should be(domain.Jwt.ClaimName(jsonPathFrom("user")))
          }
        )
      }
      "rule is defined using extended version and minimal request set of fields in JWT definition" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    jwt_authentication:
              |      name: "jwt1"
              |
              |  jwt:
              |
              |  - name: jwt1
              |    user_claim: user
              |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.jwt.id should be(JwtDef.Name("jwt1"))
            rule.settings.jwt.authorizationTokenDef should be(AuthorizationTokenDef(Header.Name.authorization, "Bearer "))
            rule.settings.jwt.checkMethod shouldBe a [SignatureCheckMethod.Hmac]
            rule.settings.jwt.userClaim should be(domain.Jwt.ClaimName(jsonPathFrom("user")))
          }
        )
      }
      "user claim can be enabled in JWT definition" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    jwt_authentication: jwt1
              |
              |  jwt:
              |
              |  - name: jwt1
              |    user_claim: user
              |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.jwt.id should be(JwtDef.Name("jwt1"))
            rule.settings.jwt.authorizationTokenDef should be(AuthorizationTokenDef(Header.Name.authorization, "Bearer "))
            rule.settings.jwt.checkMethod shouldBe a [SignatureCheckMethod.Hmac]
            rule.settings.jwt.userClaim should be(domain.Jwt.ClaimName(jsonPathFrom("user")))
          }
        )
      }
    }
    "be able to be loaded from config (token-related)" when {
      "RSA family algorithm can be used in JWT signature" in {
        val pkey = KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic
        assertDecodingSuccess(
          yaml =
            s"""
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    jwt_authentication: jwt1
              |
              |  jwt:
              |
              |  - name: jwt1
              |    user_claim: user
              |    signature_algo: "RSA"
              |    signature_key: "${Base64.getEncoder.encodeToString(pkey.getEncoded)}"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.jwt.id should be(JwtDef.Name("jwt1"))
            rule.settings.jwt.authorizationTokenDef should be(AuthorizationTokenDef(Header.Name.authorization, "Bearer "))
            rule.settings.jwt.checkMethod shouldBe a [SignatureCheckMethod.Rsa]
            rule.settings.jwt.userClaim should be(domain.Jwt.ClaimName(jsonPathFrom("user")))
          }
        )
      }
      "RSA family algorithm can be used in JWT signature and key is being read from system env in old format" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    jwt_authentication: jwt1
               |
               |  jwt:
              |
               |  - name: jwt1
               |    user_claim: user
               |    signature_algo: "RSA"
               |    signature_key: "env:SECRET_RSA"
               |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.jwt.id should be(JwtDef.Name("jwt1"))
            rule.settings.jwt.authorizationTokenDef should be(AuthorizationTokenDef(Header.Name.authorization, "Bearer "))
            rule.settings.jwt.checkMethod shouldBe a [SignatureCheckMethod.Rsa]
            rule.settings.jwt.userClaim should be(domain.Jwt.ClaimName(jsonPathFrom("user")))
          }
        )
      }
      "RSA family algorithm can be used in JWT signature and key is being read from system env in new format" in {
        val pkey = KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic
        System.setProperty("SECRET_KEY", Base64.getEncoder.encodeToString(pkey.getEncoded))
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    jwt_authentication: jwt1
               |
               |  jwt:
              |
               |  - name: jwt1
               |    user_claim: user
               |    signature_algo: "RSA"
               |    signature_key: "@{env:SECRET_RSA}"
               |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.jwt.id should be(JwtDef.Name("jwt1"))
            rule.settings.jwt.authorizationTokenDef should be(AuthorizationTokenDef(Header.Name.authorization, "Bearer "))
            rule.settings.jwt.checkMethod shouldBe a [SignatureCheckMethod.Rsa]
            rule.settings.jwt.userClaim should be(domain.Jwt.ClaimName(jsonPathFrom("user")))
          }
        )
      }
      "EC family algorithm can be used in JWT signature" in {
        val pkey = KeyPairGenerator.getInstance("EC").generateKeyPair().getPublic
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    jwt_authentication: jwt1
               |
               |  jwt:
              |
               |  - name: jwt1
               |    user_claim: user
               |    signature_algo: "EC"
               |    signature_key: "text: ${Base64.getEncoder.encodeToString(pkey.getEncoded)}"
               |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.jwt.id should be(JwtDef.Name("jwt1"))
            rule.settings.jwt.authorizationTokenDef should be(AuthorizationTokenDef(Header.Name.authorization, "Bearer "))
            rule.settings.jwt.checkMethod shouldBe a [SignatureCheckMethod.Ec]
            rule.settings.jwt.userClaim should be(domain.Jwt.ClaimName(jsonPathFrom("user")))
          }
        )
      }
      "None signature check can be used in JWT definition" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    jwt_authentication: jwt1
               |
               |  jwt:
              |
               |  - name: jwt1
               |    user_claim: user
               |    signature_algo: "NONE"
               |    external_validator:
               |      url: "http://192.168.0.1:8080/jwt"
               |      success_status_code: 204
               |      cache_ttl_in_sec: 60
               |      validate: false
               |
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = rule => {
            rule.settings.jwt.id should be(JwtDef.Name("jwt1"))
            rule.settings.jwt.authorizationTokenDef should be(AuthorizationTokenDef(Header.Name.authorization, "Bearer "))
            rule.settings.jwt.checkMethod shouldBe a [SignatureCheckMethod.NoCheck]
            rule.settings.jwt.checkMethod.asInstanceOf[SignatureCheckMethod.NoCheck].service shouldBe a[CacheableExternalAuthenticationServiceDecorator]
            rule.settings.jwt.userClaim should be(domain.Jwt.ClaimName(jsonPathFrom("user")))
          }
        )
      }
      "None signature check can be used in JWT definition with custom http client settings for external validator" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    jwt_authentication: jwt1
               |
               |  jwt:
              |
               |  - name: jwt1
               |    user_claim: user
               |    signature_algo: "NONE"
               |    external_validator:
               |      url: "http://192.168.0.1:8080/jwt"
               |      success_status_code: 204
               |      cache_ttl_in_sec: 60
               |      http_connection_settings:
               |        connection_timeout_in_sec: 1
               |        connection_request_timeout_in_sec: 10
               |        connection_pool_size: 30
               |        validate: true
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = rule => {
            rule.settings.jwt.id should be(JwtDef.Name("jwt1"))
            rule.settings.jwt.authorizationTokenDef should be(AuthorizationTokenDef(Header.Name.authorization, "Bearer "))
            rule.settings.jwt.checkMethod shouldBe a [SignatureCheckMethod.NoCheck]
            rule.settings.jwt.checkMethod.asInstanceOf[SignatureCheckMethod.NoCheck].service shouldBe a[CacheableExternalAuthenticationServiceDecorator]
            rule.settings.jwt.userClaim should be(domain.Jwt.ClaimName(jsonPathFrom("user")))
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "no JWT definition name is defined in rule setting" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    jwt_authentication:
              |
              |  jwt:
              |
              |  - name: jwt1
              |    user_claim: user
              |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue.fromString(
              """jwt_authentication: null
                |""".stripMargin
            )))
          }
        )
      }
      "JWT definition with given name is not found" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    jwt_authentication: jwt2
              |
              |  jwt:
              |
              |  - name: jwt1
              |    user_claim: user
              |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message("Cannot find `jwt` definition with name: jwt2")))
          }
        )
      }
      "no JWT definition is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    jwt_authentication: jwt1
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message("Cannot find `jwt` definition with name: jwt1")))
          }
        )
      }
      "no signature key is defined for default HMAC algorithm" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    jwt_authentication: jwt1
              |
              |  jwt:
              |
              |  - name: jwt1
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(MalformedValue.fromString(
              """- name: "jwt1"
                |""".stripMargin
            )))
          }
        )
      }
      "RSA algorithm is defined but no signature key" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    jwt_authentication: jwt1
              |
              |  jwt:
              |
              |  - name: jwt1
              |    signature_algo: "RSA"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(MalformedValue.fromString(
              """- name: "jwt1"
                |  signature_algo: "RSA"
                |""".stripMargin
            )))
          }
        )
      }
      "unrecognized algorithm is used" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    jwt_authentication: jwt1
              |
              |  jwt:
              |
              |  - name: jwt1
              |    signature_algo: "UNKNOWN"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Unrecognised algorithm family 'UNKNOWN'. Should be either of: HMAC, EC, RSA, NONE")))
          }
        )
      }
      "RSA signature key is malformed" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    jwt_authentication: jwt1
              |
              |  jwt:
              |
              |  - name: jwt1
              |    signature_algo: "RSA"
              |    signature_key: "malformed_key"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Key 'malformed_key' seems to be invalid")))
          }
        )
      }
      "RSA signature key cannot be read from system env" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    jwt_authentication: jwt1
              |
              |  jwt:
              |
              |  - name: jwt1
              |    signature_algo: "RSA"
              |    signature_key: "@{env:SECRET}"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(GeneralReadonlyrestSettingsError(Message("Cannot resolve ENV variable 'SECRET'")))
          }
        )
      }
      "EC signature key is malformed" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    jwt_authentication: jwt1
              |
              |  jwt:
              |
              |  - name: jwt1
              |    signature_algo: "EC"
              |    signature_key: "malformed_key"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Key 'malformed_key' seems to be invalid")))
          }
        )
      }
    }
  }

  override implicit protected def envVarsProvider: EnvVarsProvider = {
    case EnvVarName(env) if env.value == "SECRET_RSA" =>
      val pkey = KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic
      Some(Base64.getEncoder.encodeToString(pkey.getEncoded))
    case _ =>
      None
  }

  private val mockedHttpClientsFactory: HttpClientsFactory = {
    val httpClientMock = mock[HttpClient]
    new MockHttpClientsFactoryWithFixedHttpClient(httpClientMock)
  }
}
