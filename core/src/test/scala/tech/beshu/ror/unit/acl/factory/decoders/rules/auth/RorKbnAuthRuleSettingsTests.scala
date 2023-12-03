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

import eu.timepit.refined.auto._
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers._
import tech.beshu.ror.accesscontrol.blocks.definitions.RorKbnDef
import tech.beshu.ror.accesscontrol.blocks.definitions.RorKbnDef.SignatureCheckMethod
import tech.beshu.ror.accesscontrol.blocks.rules.auth.RorKbnAuthRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.RorKbnAuthRule.Groups
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.{GroupIdLike, GroupsLogic, PermittedGroupIds}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.{DefinitionsLevelCreationError, GeneralReadonlyrestSettingsError, RulesLevelCreationError}
import tech.beshu.ror.providers.EnvVarProvider.EnvVarName
import tech.beshu.ror.providers.EnvVarsProvider
import tech.beshu.ror.unit.acl.factory.decoders.rules.BaseRuleSettingsDecoderTest
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import java.security.KeyPairGenerator
import java.util.Base64

class RorKbnAuthRuleSettingsTests
  extends BaseRuleSettingsDecoderTest[RorKbnAuthRule] with MockFactory {

  "A RorKbnAuthRule" should {
    "be able to be loaded from config" when {
      "rule is defined using simplified version and minimal required set of fields in ROR kbn definition" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    ror_kbn_auth: kbn1
              |
              |  ror_kbn:
              |
              |  - name: kbn1
              |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.rorKbn.id should be(RorKbnDef.Name("kbn1"))
            rule.settings.rorKbn.checkMethod shouldBe a [SignatureCheckMethod.Hmac]
            rule.settings.permittedGroups should be(Groups.NotDefined)
          }
        )
      }
      "rule is defined using extended version and minimal request set of fields in ROR kbn definition" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    ror_kbn_auth:
              |      name: "kbn1"
              |
              |  ror_kbn:
              |
              |  - name: kbn1
              |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.rorKbn.id should be(RorKbnDef.Name("kbn1"))
            rule.settings.rorKbn.checkMethod shouldBe a [SignatureCheckMethod.Hmac]
            rule.settings.permittedGroups should be(Groups.NotDefined)
          }
        )
      }
      "rule is defined using extended version with groups or logic and minimal request set of fields in ROR kbn definition" in {
        val rolesKeys = List("roles", "groups", "groups_or")
        rolesKeys.foreach { roleKey =>
          assertDecodingSuccess(
            yaml =
              s"""
                 |readonlyrest:
                 |
                 |  access_control_rules:
                 |
                 |  - name: test_block1
                 |    ror_kbn_auth:
                 |      name: "kbn1"
                 |      $roleKey: ["group1*","group2"]
                 |
                 |  ror_kbn:
                 |
                 |  - name: kbn1
                 |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
                 |
                 |""".stripMargin,
            assertion = rule => {
              rule.settings.rorKbn.id should be(RorKbnDef.Name("kbn1"))
              rule.settings.rorKbn.checkMethod shouldBe a[SignatureCheckMethod.Hmac]
              rule.settings.permittedGroups should be(
                Groups.Defined(
                  GroupsLogic.Or(PermittedGroupIds(
                    UniqueNonEmptyList.of(GroupIdLike.from("group1*"), GroupId("group2"))
                  ))
                )
              )
            }
          )
        }
      }
      "rule is defined using extended version with groups and logic and minimal request set of fields in ROR kbn definition" in {
        val rolesKeys = List("roles_and", "groups_and")
        rolesKeys.foreach { roleKey =>
          assertDecodingSuccess(
            yaml =
              s"""
                |readonlyrest:
                |
                |  access_control_rules:
                |
                |  - name: test_block1
                |    ror_kbn_auth:
                |      name: "kbn1"
                |      $roleKey: ["group1*","group2"]
                |
                |  ror_kbn:
                |
                |  - name: kbn1
                |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.rorKbn.id should be(RorKbnDef.Name("kbn1"))
              rule.settings.rorKbn.checkMethod shouldBe a [SignatureCheckMethod.Hmac]
              rule.settings.permittedGroups should be(
                Groups.Defined(
                  GroupsLogic.And(PermittedGroupIds(
                    UniqueNonEmptyList.of(GroupIdLike.from("group1*"), GroupId("group2"))
                  ))
                )
              )
            }
          )
        }
      }
      "RSA family algorithm can be used in token signature" in {
        val pkey = KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic
        assertDecodingSuccess(
          yaml =
            s"""
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    ror_kbn_auth: kbn1
              |
              |  ror_kbn:
              |
              |  - name: kbn1
              |    signature_algo: "RSA"
              |    signature_key: "${Base64.getEncoder.encodeToString(pkey.getEncoded)}"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.rorKbn.id should be(RorKbnDef.Name("kbn1"))
            rule.settings.rorKbn.checkMethod shouldBe a [SignatureCheckMethod.Rsa]
            rule.settings.permittedGroups should be(Groups.NotDefined)
          }
        )
      }
      "RSA family algorithm can be used in token signature and key is being read from system env in old format" in {
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    ror_kbn_auth: kbn1
               |
               |  ror_kbn:
               |
               |  - name: kbn1
               |    signature_algo: "RSA"
               |    signature_key: "env:SECRET_RSA"
               |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.rorKbn.id should be(RorKbnDef.Name("kbn1"))
            rule.settings.rorKbn.checkMethod shouldBe a [SignatureCheckMethod.Rsa]
            rule.settings.permittedGroups should be(Groups.NotDefined)
          }
        )
      }
      "RSA family algorithm can be used in token signature and key is being read from system env in new format" in {
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
               |    ror_kbn_auth: kbn1
               |
               |  ror_kbn:
               |
               |  - name: kbn1
               |    roles_claim: groups
               |    signature_algo: "RSA"
               |    signature_key: "@{env:SECRET_RSA}"
               |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.rorKbn.id should be(RorKbnDef.Name("kbn1"))
            rule.settings.rorKbn.checkMethod shouldBe a [SignatureCheckMethod.Rsa]
            rule.settings.permittedGroups should be(Groups.NotDefined)
          }
        )
      }
      "EC family algorithm can be used in token signature" in {
        val pkey = KeyPairGenerator.getInstance("EC").generateKeyPair().getPublic
        assertDecodingSuccess(
          yaml =
            s"""
               |readonlyrest:
               |
               |  access_control_rules:
               |
               |  - name: test_block1
               |    ror_kbn_auth: kbn1
               |
               |  ror_kbn:
               |
               |  - name: kbn1
               |    signature_algo: "EC"
               |    signature_key: "text: ${Base64.getEncoder.encodeToString(pkey.getEncoded)}"
               |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.rorKbn.id should be(RorKbnDef.Name("kbn1"))
            rule.settings.rorKbn.checkMethod shouldBe a [SignatureCheckMethod.Ec]
            rule.settings.permittedGroups should be(Groups.NotDefined)
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "no ROR kbn definition name is defined in rule setting" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    ror_kbn_auth:
              |
              |  ror_kbn:
              |
              |  - name: kbn1
              |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue(
              """ror_kbn_auth: null
                |""".stripMargin
            )))
          }
        )
      }
      "ROR kbn definition with given name is not found" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    ror_kbn_auth: kbn1
              |
              |  ror_kbn:
              |
              |  - name: kbn2
              |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message("Cannot find ROR Kibana definition with name: kbn1")))
          }
        )
      }
      "no ROR kbn definition is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    ror_kbn_auth: kbn1
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message("Cannot find ROR Kibana definition with name: kbn1")))
          }
        )
      }
      "extended version of rule settings is used, but no ROR kbn definition name attribute is used" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    ror_kbn_auth:
              |      roles: ["group1","group2"]
              |
              |  ror_kbn:
              |
              |  - name: kbn1
              |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue(
              """ror_kbn_auth:
                |  roles:
                |  - "group1"
                |  - "group2"
                |""".stripMargin
            )))
          }
        )
      }
      "no ROR kbn definition name is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    ror_kbn_auth: kbn1
              |
              |  ror_kbn:
              |
              |  - signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(MalformedValue(
              """- signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
                |""".stripMargin
            )))
          }
        )
      }
      "both 'groups or' key and 'groups and' key used" in {
        List(
          ("roles", "roles_and"),
          ("groups", "groups_and")
        )
          .foreach { case (groupsOrKey, groupsAndKey) =>
            assertDecodingFailure(
              yaml =
                s"""
                   |readonlyrest:
                   |
                   |  access_control_rules:
                   |
                   |  - name: test_block1
                   |    ror_kbn_auth:
                   |      name: "kbn1"
                   |      $groupsOrKey: ["group1", "group2"]
                   |      $groupsAndKey: ["groups1", "groups2"]
                   |  ror_kbn:
                   |
                   |  - name: kbn2
                   |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
                   |
                   |""".stripMargin,
              assertion = errors => {
                errors should have size 1
                errors.head should be(RulesLevelCreationError(Message(
                  s"Please specify either '$groupsOrKey' or '$groupsAndKey' for ROR Kibana authorization rule 'kbn1'")
                ))
              }
            )
          }
      }
      "two ROR kbn definitions have the same names" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    ror_kbn_auth: kbn1
              |
              |  ror_kbn:
              |
              |  - name: kbn1
              |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
              |
              |  - name: kbn1
              |    signature_key: "123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456.123456"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(Message("ror_kbn definitions must have unique identifiers. Duplicates: kbn1")))
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
              |    ror_kbn_auth: kbn1
              |
              |  ror_kbn:
              |
              |  - name: kbn1
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(MalformedValue(
              """- name: "kbn1"
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
              |    ror_kbn_auth: kbn1
              |
              |  ror_kbn:
              |
              |  - name: kbn1
              |    signature_algo: "RSA"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(DefinitionsLevelCreationError(MalformedValue(
              """- name: "kbn1"
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
              |    ror_kbn_auth: kbn1
              |
              |  ror_kbn:
              |
              |  - name: kbn1
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
              |    ror_kbn_auth: kbn1
              |
              |  ror_kbn:
              |
              |  - name: kbn1
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
              |    ror_kbn_auth: kbn1
              |
              |  ror_kbn:
              |
              |  - name: kbn1
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
              |    ror_kbn_auth: kbn1
              |
              |  ror_kbn:
              |
              |  - name: kbn1
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
}
