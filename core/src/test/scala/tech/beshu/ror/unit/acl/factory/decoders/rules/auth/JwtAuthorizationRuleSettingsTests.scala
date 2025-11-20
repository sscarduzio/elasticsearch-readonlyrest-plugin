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
import tech.beshu.ror.accesscontrol.blocks.definitions.JwtDef
import tech.beshu.ror.accesscontrol.blocks.definitions.JwtDef.SignatureCheckMethod
import tech.beshu.ror.accesscontrol.blocks.rules.auth.JwtAuthorizationRule
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.providers.EnvVarProvider.EnvVarName
import tech.beshu.ror.providers.EnvVarsProvider
import tech.beshu.ror.unit.acl.factory.decoders.rules.BaseRuleSettingsDecoderTest
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import java.security.KeyPairGenerator
import java.util.Base64

class JwtAuthorizationRuleSettingsTests
  extends BaseRuleSettingsDecoderTest[JwtAuthorizationRule]
    with MockFactory {

  "A JwtAuthorizationRule" should {
    "be able to be loaded from config" when {
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
                 |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
                 |    jwt_authorization:
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
              rule.settings.jwt.checkMethod shouldBe a[SignatureCheckMethod.Hmac]
              rule.settings.jwt.userClaim should be(None)
              rule.settings.jwt.groupsConfig should be(None)
              rule.settings.groupsLogic should be(GroupsLogic.AnyOf(GroupIds(
                UniqueNonEmptyList.of(GroupIdLike.from("group1*"), GroupId("group2"))
              )))
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
                 |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
                 |    jwt_authorization:
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
              rule.settings.jwt.checkMethod shouldBe a[SignatureCheckMethod.Hmac]
              rule.settings.jwt.userClaim should be(None)
              rule.settings.jwt.groupsConfig should be(None)
              rule.settings.groupsLogic should be(GroupsLogic.AllOf(GroupIds(
                UniqueNonEmptyList.of(GroupIdLike.from("group1*"), GroupId("group2"))
              )))
            }
          )
        }
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
              |    jwt_authorization:
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
              """jwt_authorization: null
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
              |    jwt_authorization: jwt2
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
              |    jwt_authorization: jwt1
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
              |    jwt_authorization:
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
              """jwt_authorization:
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
                   |    jwt_authorization:
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
    }
  }

  override implicit protected def envVarsProvider: EnvVarsProvider = {
    case EnvVarName(env) if env.value == "SECRET_RSA" =>
      val pkey = KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic
      Some(Base64.getEncoder.encodeToString(pkey.getEncoded))
    case _ =>
      None
  }
}
