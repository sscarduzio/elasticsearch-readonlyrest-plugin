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
import tech.beshu.ror.accesscontrol.blocks.definitions.PkiDef.{ExtractionSpec, SanType}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.{PkiAuthRule, PkiAuthenticationRule, PkiAuthorizationRule}
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.{GroupIds, GroupsLogic, User}
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.{
  BlocksLevelCreationError,
  RulesLevelCreationError
}
import tech.beshu.ror.unit.acl.factory.decoders.rules.BaseRuleSettingsDecoderTest
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class PkiAuthenticationRuleSettingsTests extends BaseRuleSettingsDecoderTest[PkiAuthenticationRule] {

  "A PkiAuthenticationRule" should {
    "be able to be loaded from settings" when {
      "the PKI is referenced by name only" in {
        assertDecodingSuccess(
          yaml = """
                   |readonlyrest:
                   |
                   |  access_control_rules:
                   |
                   |  - name: test_block1
                   |    pki_authentication: corporate_pki
                   |
                   |  pkis:
                   |  - name: corporate_pki
                   |
                   |""".stripMargin,
          assertion = rule => {
            rule.settings.pki.id.value.value should be("corporate_pki")
            rule.settings.pki.usersExtraction should be(ExtractionSpec.SubjectDnAttribute(nes("CN")))
            rule.settings.userIds should be(None)
          }
        )
      }
      "an accepted users list is given" in {
        assertDecodingSuccess(
          yaml = """
                   |readonlyrest:
                   |
                   |  access_control_rules:
                   |
                   |  - name: test_block1
                   |    pki_authentication:
                   |      name: corporate_pki
                   |      users: ["logstash-*", "beats-*"]
                   |
                   |  pkis:
                   |  - name: corporate_pki
                   |
                   |""".stripMargin,
          assertion = rule => {
            rule.settings.userIds should be(
              Some(UniqueNonEmptyList.of(User.Id("logstash-*"), User.Id("beats-*")))
            )
          }
        )
      }
      "the PKI reads the identity from a subject alternative name" in {
        assertDecodingSuccess(
          yaml = """
                   |readonlyrest:
                   |
                   |  access_control_rules:
                   |
                   |  - name: test_block1
                   |    pki_authentication: ad_pki
                   |
                   |  pkis:
                   |  - name: ad_pki
                   |    users:
                   |      mode: san
                   |      san_type: upn
                   |
                   |""".stripMargin,
          assertion = rule => {
            rule.settings.pki.usersExtraction should be(ExtractionSpec.San(SanType.Upn, None))
          }
        )
      }
      "a PKI with a groups section is used for authentication only" in {
        assertDecodingSuccess(
          yaml = """
                   |readonlyrest:
                   |
                   |  access_control_rules:
                   |
                   |  - name: test_block1
                   |    pki_authentication: corporate_pki
                   |
                   |  pkis:
                   |  - name: corporate_pki
                   |    groups:
                   |      group_id_attribute: "OU"
                   |
                   |""".stripMargin,
          assertion = rule => {
            rule.settings.pki.groupsExtraction should be(Some(ExtractionSpec.SubjectDnAttribute(nes("OU"))))
          }
        )
      }
    }
    "not be able to be loaded from settings" when {
      "the referenced PKI is not defined" in {
        assertDecodingFailure(
          yaml = """
                   |readonlyrest:
                   |
                   |  access_control_rules:
                   |
                   |  - name: test_block1
                   |    pki_authentication: corporate_pki
                   |
                   |  pkis:
                   |  - name: another_pki
                   |
                   |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message("Cannot find PKI with name: corporate_pki")))
          }
        )
      }
      "no pkis section is defined at all" in {
        assertDecodingFailure(
          yaml = """
                   |readonlyrest:
                   |
                   |  access_control_rules:
                   |
                   |  - name: test_block1
                   |    pki_authentication: corporate_pki
                   |
                   |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message("Cannot find PKI with name: corporate_pki")))
          }
        )
      }
    }
  }

}

class PkiAuthorizationRuleSettingsTests extends BaseRuleSettingsDecoderTest[PkiAuthorizationRule] {

  "A PkiAuthorizationRule" should {
    "be able to be loaded from settings" when {
      "groups are read from the certificate" in {
        assertDecodingSuccess(
          yaml = """
                   |readonlyrest:
                   |
                   |  access_control_rules:
                   |
                   |  - name: test_block1
                   |    auth_key: user:pass
                   |    pki_authorization:
                   |      name: corporate_pki
                   |      groups: ["ingest"]
                   |
                   |  pkis:
                   |  - name: corporate_pki
                   |    groups:
                   |      group_id_attribute: "OU"
                   |
                   |""".stripMargin,
          assertion = rule => {
            rule.settings.pki.id.value.value should be("corporate_pki")
            rule.settings.groupsLogic should be(
              GroupsLogic.AnyOf(GroupIds(UniqueNonEmptyList.of(GroupId("ingest"))))
            )
          }
        )
      }
    }
    "not be able to be loaded from settings" when {
      "the referenced PKI defines no groups section" in {
        assertDecodingFailure(
          yaml = """
                   |readonlyrest:
                   |
                   |  access_control_rules:
                   |
                   |  - name: test_block1
                   |    auth_key: user:pass
                   |    pki_authorization:
                   |      name: corporate_pki
                   |      groups: ["ingest"]
                   |
                   |  pkis:
                   |  - name: corporate_pki
                   |
                   |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(
              RulesLevelCreationError(
                Message(
                  "PKI: corporate_pki cannot be used in 'pki_authorization' rule, because it doesn't define a 'groups' section"
                )
              )
            )
          }
        )
      }
      "no authentication rule sits alongside it" in {
        assertDecodingFailure(
          yaml = """
                   |readonlyrest:
                   |
                   |  access_control_rules:
                   |
                   |  - name: test_block1
                   |    pki_authorization:
                   |      name: corporate_pki
                   |      groups: ["ingest"]
                   |
                   |  pkis:
                   |  - name: corporate_pki
                   |    groups:
                   |      group_id_attribute: "OU"
                   |
                   |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head shouldBe a[BlocksLevelCreationError]
          }
        )
      }
    }
  }

}

class PkiAuthRuleSettingsTests extends BaseRuleSettingsDecoderTest[PkiAuthRule] {

  "A PkiAuthRule" should {
    "be able to be loaded from settings" when {
      "the PKI provides both the identity and the groups" in {
        assertDecodingSuccess(
          yaml = """
                   |readonlyrest:
                   |
                   |  access_control_rules:
                   |
                   |  - name: test_block1
                   |    pki_auth:
                   |      name: service_pki
                   |      groups: ["ingest_services"]
                   |
                   |  pkis:
                   |  - name: service_pki
                   |    users:
                   |      user_id_attribute: "CN"
                   |    groups:
                   |      group_id_attribute: "OU"
                   |
                   |""".stripMargin,
          assertion = rule => {
            rule.authentication.settings.pki.id.value.value should be("service_pki")
            rule.authorization.settings.pki.id.value.value should be("service_pki")
            rule.authentication.settings.userIds should be(None)
          }
        )
      }
    }
    "not be able to be loaded from settings" when {
      "the referenced PKI defines no groups section" in {
        assertDecodingFailure(
          yaml = """
                   |readonlyrest:
                   |
                   |  access_control_rules:
                   |
                   |  - name: test_block1
                   |    pki_auth:
                   |      name: service_pki
                   |      groups: ["ingest_services"]
                   |
                   |  pkis:
                   |  - name: service_pki
                   |
                   |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(
              RulesLevelCreationError(
                Message(
                  "PKI: service_pki cannot be used in 'pki_auth' rule, because it doesn't define a 'groups' section"
                )
              )
            )
          }
        )
      }
    }
  }

}
