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
import tech.beshu.ror.accesscontrol.blocks.definitions.JwtDef.{GroupsConfig, SignatureCheckMethod}
import tech.beshu.ror.accesscontrol.blocks.definitions.{CacheableExternalAuthenticationServiceDecorator, JwtDef}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.JwtAuthRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.JwtAuthRule.Groups
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory.HttpClient
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.{DefinitionsLevelCreationError, GeneralReadonlyrestSettingsError, RulesLevelCreationError}
import tech.beshu.ror.mocks.MockHttpClientsFactoryWithFixedHttpClient
import tech.beshu.ror.providers.EnvVarProvider.EnvVarName
import tech.beshu.ror.providers.EnvVarsProvider
import tech.beshu.ror.unit.acl.factory.decoders.rules.BaseRuleSettingsDecoderTest
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import java.security.KeyPairGenerator
import java.util.Base64

class JwtAuthRuleSettingsTests
  extends BaseRuleSettingsDecoderTest[JwtAuthRule]
    with MockFactory {

  "A JwtAuthRule" should {
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
              |    jwt_auth: jwt1
              |
              |  jwt:
              |
              |  - name: jwt1
              |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.jwt.id should be(JwtDef.Name("jwt1"))
            rule.settings.jwt.authorizationTokenDef should be(AuthorizationTokenDef(Header.Name.authorization, "Bearer "))
            rule.settings.jwt.checkMethod shouldBe a [SignatureCheckMethod.Hmac]
            rule.settings.jwt.userClaim should be(None)
            rule.settings.jwt.groupsConfig should be(None)
            rule.settings.permittedGroups should be(Groups.NotDefined)
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
              |    jwt_auth:
              |      name: "jwt1"
              |
              |  jwt:
              |
              |  - name: jwt1
              |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.jwt.id should be(JwtDef.Name("jwt1"))
            rule.settings.jwt.authorizationTokenDef should be(AuthorizationTokenDef(Header.Name.authorization, "Bearer "))
            rule.settings.jwt.checkMethod shouldBe a [SignatureCheckMethod.Hmac]
            rule.settings.jwt.userClaim should be(None)
            rule.settings.jwt.groupsConfig should be(None)
            rule.settings.permittedGroups should be(Groups.NotDefined)
          }
        )
      }
      "rule is defined using extended version with groups 'or' logic and minimal request set of fields in JWT definition" in {
        val ruleKeys = List("roles", "groups", "groups_or")
        ruleKeys.foreach { ruleKey =>
          assertDecodingSuccess(
            yaml =
              s"""
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    jwt_auth:
                |      name: "jwt1"
                |      $ruleKey: ["group1*","group2"]
                |
                |  jwt:
                |
                |  - name: jwt1
                |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.jwt.id should be(JwtDef.Name("jwt1"))
              rule.settings.jwt.authorizationTokenDef should be(AuthorizationTokenDef(Header.Name.authorization, "Bearer "))
              rule.settings.jwt.checkMethod shouldBe a [SignatureCheckMethod.Hmac]
              rule.settings.jwt.userClaim should be(None)
              rule.settings.jwt.groupsConfig should be(None)
              rule.settings.permittedGroups should be(Groups.Defined(GroupsLogic.AnyOf(GroupIds(
                UniqueNonEmptyList.of(GroupIdLike.from("group1*"), GroupId("group2"))
              ))))
            }
          )
        }
      }
      "rule is defined using extended version with groups 'and' logic and minimal request set of fields in JWT definition" in {
        val ruleKeys = List("roles_and", "groups_and")
        ruleKeys.foreach { ruleKey =>
          assertDecodingSuccess(
            yaml =
              s"""
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    jwt_auth:
                |      name: "jwt1"
                |      $ruleKey: ["group1*","group2"]
                |
                |  jwt:
                |
                |  - name: jwt1
                |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.jwt.id should be(JwtDef.Name("jwt1"))
              rule.settings.jwt.authorizationTokenDef should be(AuthorizationTokenDef(Header.Name.authorization, "Bearer "))
              rule.settings.jwt.checkMethod shouldBe a [SignatureCheckMethod.Hmac]
              rule.settings.jwt.userClaim should be(None)
              rule.settings.jwt.groupsConfig should be(None)
              rule.settings.permittedGroups should be(Groups.Defined(GroupsLogic.AllOf(GroupIds(
                UniqueNonEmptyList.of(GroupIdLike.from("group1*"), GroupId("group2"))
              ))))
            }
          )
        }

      }
      "token header name can be changes in JWT definition" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    jwt_auth: jwt1
              |
              |  jwt:
              |
              |  - name: jwt1
              |    header_name: X-JWT-Custom-Header
              |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.jwt.id should be(JwtDef.Name("jwt1"))
            rule.settings.jwt.authorizationTokenDef should be(AuthorizationTokenDef(headerNameFrom("X-JWT-Custom-Header"), "Bearer "))
            rule.settings.jwt.checkMethod shouldBe a [SignatureCheckMethod.Hmac]
            rule.settings.jwt.userClaim should be(None)
            rule.settings.jwt.groupsConfig should be(None)
            rule.settings.permittedGroups should be(Groups.NotDefined)
          }
        )
      }
      "token prefix can be changes in JWT definition for custom token header" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    jwt_auth: jwt1
              |
              |  jwt:
              |
              |  - name: jwt1
              |    header_name: X-JWT-Custom-Header
              |    header_prefix: "MyPrefix "
              |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.jwt.id should be(JwtDef.Name("jwt1"))
            rule.settings.jwt.authorizationTokenDef should be(AuthorizationTokenDef(headerNameFrom("X-JWT-Custom-Header"), "MyPrefix "))
            rule.settings.jwt.checkMethod shouldBe a [SignatureCheckMethod.Hmac]
            rule.settings.jwt.userClaim should be(None)
            rule.settings.jwt.groupsConfig should be(None)
            rule.settings.permittedGroups should be(Groups.NotDefined)
          }
        )
      }
      "token prefix can be changes in JWT definition for standard token header" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    jwt_auth: jwt1
              |
              |  jwt:
              |
              |  - name: jwt1
              |    header_prefix: "MyPrefix "
              |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.jwt.id should be(JwtDef.Name("jwt1"))
            rule.settings.jwt.authorizationTokenDef should be(AuthorizationTokenDef(Header.Name.authorization, "MyPrefix "))
            rule.settings.jwt.checkMethod shouldBe a [SignatureCheckMethod.Hmac]
            rule.settings.jwt.userClaim should be(None)
            rule.settings.jwt.groupsConfig should be(None)
            rule.settings.permittedGroups should be(Groups.NotDefined)
          }
        )
      }
      "custom prefix attribute is empty" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    jwt_auth: jwt1
              |
              |  jwt:
              |
              |  - name: jwt1
              |    header_name: X-JWT-Custom-Header
              |    header_prefix: ""
              |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.jwt.id should be(JwtDef.Name("jwt1"))
            rule.settings.jwt.authorizationTokenDef should be(AuthorizationTokenDef(headerNameFrom("X-JWT-Custom-Header"), ""))
            rule.settings.jwt.checkMethod shouldBe a [SignatureCheckMethod.Hmac]
            rule.settings.jwt.userClaim should be(None)
            rule.settings.jwt.groupsConfig should be(None)
            rule.settings.permittedGroups should be(Groups.NotDefined)
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
              |    jwt_auth: jwt1
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
            rule.settings.jwt.userClaim should be(Some(domain.Jwt.ClaimName(jsonPathFrom("user"))))
            rule.settings.jwt.groupsConfig should be(None)
            rule.settings.permittedGroups should be(Groups.NotDefined)
          }
        )
      }
      "group IDs claim can be enabled in JWT definition" in {
        val claimKeys = List("roles_claim", "groups_claim", "group_ids_claim")
        claimKeys.foreach { claimKey =>
          assertDecodingSuccess(
            yaml =
              s"""
                |readonlyrest:
                |
                |  access_control_rules:
                |
                |  - name: test_block1
                |    jwt_auth: jwt1
                |
                |  jwt:
                |
                |  - name: jwt1
                |    $claimKey: groups
                |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.jwt.id should be(JwtDef.Name("jwt1"))
              rule.settings.jwt.authorizationTokenDef should be(AuthorizationTokenDef(Header.Name.authorization, "Bearer "))
              rule.settings.jwt.checkMethod shouldBe a[SignatureCheckMethod.Hmac]
              rule.settings.jwt.userClaim should be(None)
              rule.settings.jwt.groupsConfig should be(Some(GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None)))
              rule.settings.permittedGroups should be(Groups.NotDefined)
            }
          )
        }
      }
      "group names claim can be enabled in JWT definition" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    jwt_auth: jwt1
               |
               |  jwt:
               |
               |  - name: jwt1
               |    group_ids_claim: groups
               |    group_names_claim: group_names
               |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
               |
               |""".stripMargin,
          assertion = rule => {
            rule.settings.jwt.id should be(JwtDef.Name("jwt1"))
            rule.settings.jwt.authorizationTokenDef should be(AuthorizationTokenDef(Header.Name.authorization, "Bearer "))
            rule.settings.jwt.checkMethod shouldBe a[SignatureCheckMethod.Hmac]
            rule.settings.jwt.userClaim should be(None)
            rule.settings.jwt.groupsConfig should be(Some(GroupsConfig(
              idsClaim = domain.Jwt.ClaimName(jsonPathFrom("groups")),
              namesClaim = Some(domain.Jwt.ClaimName(jsonPathFrom("group_names")))
            )))
            rule.settings.permittedGroups should be(Groups.NotDefined)
          }
        )
      }
      "groups claim can be enabled in JWT definition and is a http address" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    jwt_auth: jwt1
              |
              |  jwt:
              |
              |  - name: jwt1
              |    group_ids_claim: "https://{domain}/claims/roles"
              |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.jwt.id should be(JwtDef.Name("jwt1"))
            rule.settings.jwt.authorizationTokenDef should be(AuthorizationTokenDef(Header.Name.authorization, "Bearer "))
            rule.settings.jwt.checkMethod shouldBe a [SignatureCheckMethod.Hmac]
            rule.settings.jwt.userClaim should be(None)
            rule.settings.jwt.groupsConfig should be(Some(GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("https://{domain}/claims/roles")), None)))
            rule.settings.permittedGroups should be(Groups.NotDefined)
          }
        )
      }
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
              |    jwt_auth: jwt1
              |
              |  jwt:
              |
              |  - name: jwt1
              |    group_ids_claim: groups
              |    signature_algo: "RSA"
              |    signature_key: "${Base64.getEncoder.encodeToString(pkey.getEncoded)}"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.jwt.id should be(JwtDef.Name("jwt1"))
            rule.settings.jwt.authorizationTokenDef should be(AuthorizationTokenDef(Header.Name.authorization, "Bearer "))
            rule.settings.jwt.checkMethod shouldBe a [SignatureCheckMethod.Rsa]
            rule.settings.jwt.userClaim should be(None)
            rule.settings.jwt.groupsConfig should be(Some(GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None)))
            rule.settings.permittedGroups should be(Groups.NotDefined)
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
               |    jwt_auth: jwt1
               |
               |  jwt:
               |
               |  - name: jwt1
               |    group_ids_claim: groups
               |    signature_algo: "RSA"
               |    signature_key: "env:SECRET_RSA"
               |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.jwt.id should be(JwtDef.Name("jwt1"))
            rule.settings.jwt.authorizationTokenDef should be(AuthorizationTokenDef(Header.Name.authorization, "Bearer "))
            rule.settings.jwt.checkMethod shouldBe a [SignatureCheckMethod.Rsa]
            rule.settings.jwt.userClaim should be(None)
            rule.settings.jwt.groupsConfig should be(Some(GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None)))
            rule.settings.permittedGroups should be(Groups.NotDefined)
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
               |    jwt_auth: jwt1
               |
               |  jwt:
               |
               |  - name: jwt1
               |    group_ids_claim: groups
               |    signature_algo: "RSA"
               |    signature_key: "@{env:SECRET_RSA}"
               |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.jwt.id should be(JwtDef.Name("jwt1"))
            rule.settings.jwt.authorizationTokenDef should be(AuthorizationTokenDef(Header.Name.authorization, "Bearer "))
            rule.settings.jwt.checkMethod shouldBe a [SignatureCheckMethod.Rsa]
            rule.settings.jwt.userClaim should be(None)
            rule.settings.jwt.groupsConfig should be(Some(GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None)))
            rule.settings.permittedGroups should be(Groups.NotDefined)
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
               |    jwt_auth: jwt1
               |
               |  jwt:
               |
               |  - name: jwt1
               |    group_ids_claim: groups
               |    signature_algo: "EC"
               |    signature_key: "text: ${Base64.getEncoder.encodeToString(pkey.getEncoded)}"
               |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.jwt.id should be(JwtDef.Name("jwt1"))
            rule.settings.jwt.authorizationTokenDef should be(AuthorizationTokenDef(Header.Name.authorization, "Bearer "))
            rule.settings.jwt.checkMethod shouldBe a [SignatureCheckMethod.Ec]
            rule.settings.jwt.userClaim should be(None)
            rule.settings.jwt.groupsConfig should be(Some(GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")), None)))
            rule.settings.permittedGroups should be(Groups.NotDefined)
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
               |    jwt_auth: jwt1
               |
               |  jwt:
               |
               |  - name: jwt1
               |    group_ids_claim: groups
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
            rule.settings.jwt.userClaim should be(None)
            rule.settings.jwt.groupsConfig should be(Some(GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")),None)))
            rule.settings.permittedGroups should be(Groups.NotDefined)
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
               |    jwt_auth: jwt1
               |
               |  jwt:
               |
               |  - name: jwt1
               |    group_ids_claim: groups
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
            rule.settings.jwt.userClaim should be(None)
            rule.settings.jwt.groupsConfig should be(Some(GroupsConfig(domain.Jwt.ClaimName(jsonPathFrom("groups")),None)))
            rule.settings.permittedGroups should be(Groups.NotDefined)
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
              |    jwt_auth:
              |
              |  jwt:
              |
              |  - name: jwt1
              |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue.fromString(
              """jwt_auth: null
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
              |    jwt_auth: jwt2
              |
              |  jwt:
              |
              |  - name: jwt1
              |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message("Cannot find JWT definition with name: jwt2")))
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
              |    jwt_auth: jwt1
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message("Cannot find JWT definition with name: jwt1")))
          }
        )
      }
      "extended version of rule settings is used, but no JWT definition name attribute is used" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    jwt_auth:
              |      roles: ["group1","group2"]
              |
              |  jwt:
              |
              |  - name: jwt1
              |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue.fromString(
              """jwt_auth:
                |  roles:
                |  - "group1"
                |  - "group2"
                |""".stripMargin
            )))
          }
        )
      }
      "extended version of rule settings is used, but both 'groups or' key and 'groups and' key used" in {
        List(
          ("roles", "roles_and"),
          ("groups", "groups_and")
        )
          .foreach { case (groupsAnyOfKey, groupsAllOfKey) =>
            assertDecodingFailure(
              yaml =
                s"""
                   |readonlyrest:
                   |
                   |  access_control_rules:
                   |
                   |  - name: test_block1
                   |    jwt_auth:
                   |      name: "jwt1"
                   |      $groupsAnyOfKey: ["group1","group2"]
                   |      $groupsAllOfKey: ["group1","group2"]
                   |
                   |  jwt:
                   |
                   |  - name: jwt1
                   |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
                   |
                   |""".stripMargin,
              assertion = errors => {
                errors should have size 1
                errors.head should be(RulesLevelCreationError(Message(
                  s"Please specify either '$groupsAnyOfKey' or '$groupsAllOfKey' for JWT authorization rule 'jwt1'"
                )))
              }
            )
          }
      }
      "no JWT definition name is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    jwt_auth: jwt1
              |
              |  jwt:
              |
              |  - signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(MalformedValue.fromString(
              """- signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
                |""".stripMargin
            )))
          }
        )
      }
      "two JTW definitions have the same names" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    jwt_auth: jwt1
              |
              |  jwt:
              |
              |  - name: jwt1
              |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
              |
              |  - name: jwt1
              |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("jwt definitions must have unique identifiers. Duplicates: jwt1")))
          }
        )
      }
      "custom header attribute is used but no name is set" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    jwt_auth: jwt1
              |
              |  jwt:
              |
              |  - name: jwt1
              |    header_name: ""
              |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Header name cannot be empty string")))
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
              |    jwt_auth: jwt1
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
      "RSA algorithm is defined but on signature key" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    jwt_auth: jwt1
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
              |    jwt_auth: jwt1
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
              |    jwt_auth: jwt1
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
              |    jwt_auth: jwt1
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
              |    jwt_auth: jwt1
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
      "no signature check is used but required external validation service is not defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    jwt_auth: jwt1
              |
              |  jwt:
              |
              |  - name: jwt1
              |    signature_algo: "NONE"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("External validator has to be defined when signature algorithm is None")))
          }
        )
      }
      "no signature check is used and required external validation service settings are malformed" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    jwt_auth: jwt1
              |
              |  jwt:
              |
              |  - name: jwt1
              |    signature_algo: "NONE"
              |    external_validator:
              |      url_of_validator: "unknown_addr"
              |
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("External validator has to be defined when signature algorithm is None")))
          }
        )
      }
      "external validator custom http client settings is defined together with validate at rule level" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    jwt_auth: jwt1
              |
              |  jwt:
              |
              |  - name: jwt1
              |    signature_algo: "NONE"
              |    external_validator:
              |      url: "http://192.168.0.1:8080/jwt"
              |      validate: false
              |      http_connection_settings:
              |        connection_timeout_in_sec: 1
              |        connection_request_timeout_in_sec: 10
              |        connection_pool_size: 30
              |        validate: true
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("If 'http_connection_settings' are used, 'validate' should be placed in that section")))
          }
        )
      }
      "external validator custom http client connection timeout is negative" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    jwt_auth: jwt1
              |
              |  jwt:
              |
              |  - name: jwt1
              |    signature_algo: "NONE"
              |    external_validator:
              |      url: "http://192.168.0.1:8080/jwt"
              |      http_connection_settings:
              |        connection_timeout_in_sec: -10
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Only positive values allowed. Found: -10 seconds")))
          }
        )
      }
      "external validator custom http client request timeout is negative" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    jwt_auth: jwt1
              |
              |  jwt:
              |
              |  - name: jwt1
              |    signature_algo: "NONE"
              |    external_validator:
              |      url: "http://192.168.0.1:8080/jwt"
              |      http_connection_settings:
              |        connection_request_timeout_in_sec: -10
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Only positive values allowed. Found: -10 seconds")))
          }
        )
      }
      "external validator custom http client connection pool size is negative" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    jwt_auth: jwt1
              |
              |  jwt:
              |
              |  - name: jwt1
              |    signature_algo: "NONE"
              |    external_validator:
              |      url: "http://192.168.0.1:8080/jwt"
              |      http_connection_settings:
              |        connection_pool_size: -10
              |""".stripMargin,
          httpClientsFactory = mockedHttpClientsFactory,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("Only positive values allowed. Found: -10")))
          }
        )
      }
      "group IDs claim json path is malformed" in {
        assertDecodingFailure(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    jwt_auth: jwt1
               |
               |  jwt:
               |
               |  - name: jwt1
               |    group_ids_claim: "$$..groups[?.id)].id_malformed"
               |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
               |
               |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message(
              """Cannot compile '$..groups[?.id)].id_malformed' to JSON path""".stripMargin
            )))
          }
        )

      }
      "group names claim json path is malformed" in {
        assertDecodingFailure(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    jwt_auth: jwt1
               |
               |  jwt:
               |
               |  - name: jwt1
               |    group_ids_claim: groups
               |    group_names_claim: "$$..groups[?.id)].name_malformed"
               |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
               |
               |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message(
              """Cannot compile '$..groups[?.id)].name_malformed' to JSON path""".stripMargin
            )))
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
