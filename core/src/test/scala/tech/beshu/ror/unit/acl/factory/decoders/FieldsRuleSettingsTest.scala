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
package tech.beshu.ror.unit.acl.factory.decoders

import org.scalatest.Matchers._
import tech.beshu.ror.accesscontrol.blocks.rules.FieldsRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.{AlreadyResolved, ToBeResolved}
import tech.beshu.ror.accesscontrol.domain.DocumentField
import tech.beshu.ror.accesscontrol.domain.DocumentField.AccessMode
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.utils.TestsUtils._

class FieldsRuleSettingsTest extends BaseRuleSettingsDecoderTest[FieldsRule] {

  "A FieldsRule" should {
    "be able to be loaded from config" when {
      "only one field is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    fields: "field1"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.fields.head should be(AlreadyResolved(DocumentField("field1".nonempty).nel))
            rule.settings.accessMode should be(AccessMode.Whitelist)
          }
        )
      }
      "two fields are defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    fields: [field1, field2]
              |
              |""".stripMargin,
          assertion = rule => {
            val decodedFields = rule.settings.fields
            decodedFields.head should be(AlreadyResolved(DocumentField("field1".nonempty).nel))
            decodedFields.last should be(AlreadyResolved(DocumentField("field2".nonempty).nel))
            rule.settings.accessMode should be(AccessMode.Whitelist)
          }
        )
      }
      "only one blacklisted field is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    fields: "~field1"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.fields.head should be(AlreadyResolved(DocumentField("field1".nonempty).nel))
            rule.settings.accessMode should be(AccessMode.Blacklist)
          }
        )
      }
      "two blacklisted fields are defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    fields: [~field1, ~field2]
              |
              |""".stripMargin,
          assertion = rule => {
            val decodedFields = rule.settings.fields
            decodedFields.head should be(AlreadyResolved(DocumentField("field1".nonempty).nel))
            decodedFields.last should be(AlreadyResolved(DocumentField("field2".nonempty).nel))
            rule.settings.accessMode should be(AccessMode.Blacklist)
          }
        )
      }
      "field is defined with variable" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    fields: ["@{user}", "field2"]
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.fields.head shouldBe a [ToBeResolved[_]]
            rule.settings.fields.last should be(AlreadyResolved(DocumentField("field2".nonempty).nel))
            rule.settings.accessMode should be(AccessMode.Whitelist)
          }
        )
      }

    }
    "not be able to be loaded from config" when {
      "no field is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    fields:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue(
              """fields: null
                |""".stripMargin
            )))
          }
        )
      }
      "blacklisted fields are mixed with whitelisted ones" when {
        "no variable is used" in {
          assertDecodingFailure(
            yaml =
              """
                |readonlyrest:
                |
                |  access_control_rules:
                |
                |  - name: test_block1
                |    fields: [field1, ~field2]
                |
                |""".stripMargin,
            assertion = errors => {
              errors should have size 1
              errors.head should be(RulesLevelCreationError(Message(
                "fields should all be negated (i.e. '~field1') or all without negation (i.e. 'field1') Found: 'field1','~field2'"
              )))
            }
          )
        }
        "variable and already resolved value are used" in {
          assertDecodingFailure(
            yaml =
              """
                |readonlyrest:
                |
                |  access_control_rules:
                |
                |  - name: test_block1
                |    fields: ["@{user}", "~field2"]
                |
                |""".stripMargin,
            assertion = errors => {
              errors should have size 1
              errors.head should be(RulesLevelCreationError(Message(
                "fields should all be negated (i.e. '~field1') or all without negation (i.e. 'field1') Found: '@{user}','~field2'"
              )))
            }
          )
        }
        "only variables are used" in {
          assertDecodingFailure(
            yaml =
              """
                |readonlyrest:
                |
                |  access_control_rules:
                |
                |  - name: test_block1
                |    fields: ["@{user}", "~@{user}.name"]
                |
                |""".stripMargin,
            assertion = errors => {
              errors should have size 1
              errors.head should be(RulesLevelCreationError(Message(
                "fields should all be negated (i.e. '~field1') or all without negation (i.e. 'field1') Found: '@{user}','~@{user}.name'"
              )))
            }
          )
        }
      }
      "disallowed fields are defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    fields: [field1, _type, _uid]
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message(
              "The fields rule cannot contain always-allowed fields: _routing,_ttl,_index,_type,_size,_seq_no,_parent,_id,_uid,_version,_primary_term,_timestamp"
            )))
          }
        )
      }
    }
  }
}
