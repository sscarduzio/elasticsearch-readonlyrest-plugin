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

import cats.data.NonEmptySet
import org.scalatest.Matchers._
import tech.beshu.ror.accesscontrol.blocks.rules.FieldsRule
import tech.beshu.ror.accesscontrol.domain.DocumentField
import tech.beshu.ror.accesscontrol.domain.DocumentField.ADocumentField
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.orders.documentFieldOrder
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
            val expectedFields: NonEmptySet[DocumentField] = NonEmptySet.of(ADocumentField("field1".nonempty))
            rule.settings.fields should be(expectedFields)
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
            val expectedFields: NonEmptySet[DocumentField] = NonEmptySet.of(
              ADocumentField("field1".nonempty), ADocumentField("field2".nonempty)
            )
            rule.settings.fields should be(expectedFields)
          }
        )
      }
      "only one negated field is defined" in {
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
            val expectedFields: NonEmptySet[DocumentField] = NonEmptySet.of(ADocumentField("field1".nonempty))
            rule.settings.fields should be(expectedFields)
          }
        )
      }
      "two negated fields are defined" in {
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
            val expectedFields: NonEmptySet[DocumentField] = NonEmptySet.of(
              ADocumentField("field1".nonempty), ADocumentField("field2".nonempty)
            )
            rule.settings.fields should be(expectedFields)
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
      "negated fields are mixed with non-negated ones" in {
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
              "fields should all be negated (i.e. '~field1') or all without negation (i.e. 'field1') Found: field1,field2"
            )))
          }
        )
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
