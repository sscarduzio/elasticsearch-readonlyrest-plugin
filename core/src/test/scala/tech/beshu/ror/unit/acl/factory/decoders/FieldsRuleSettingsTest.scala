package tech.beshu.ror.unit.acl.factory.decoders

import cats.data.NonEmptySet
import org.scalatest.Matchers._
import tech.beshu.ror.TestsUtils.jsonFrom
import tech.beshu.ror.acl.blocks.rules.FieldsRule
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.aDomain.DocumentField
import tech.beshu.ror.acl.aDomain.DocumentField.{ADocumentField, NegatedDocumentField}
import tech.beshu.ror.acl.orders.documentFieldOrder

class FieldsRuleSettingsTest extends RuleSettingsDecoderTest[FieldsRule] {

  "A FieldsRule" should {
    "be able to read from config" when {
      "only one field is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    fields: "field1"
              |
              |""".stripMargin,
          assertion = rule => {
            val expectedFields: NonEmptySet[DocumentField] = NonEmptySet.of(ADocumentField("field1"), NegatedDocumentField("_all"))
            rule.settings.fields should be(expectedFields)
          }
        )
      }
      "two fields are defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    fields: [field1, field2]
              |
              |""".stripMargin,
          assertion = rule => {
            val expectedFields: NonEmptySet[DocumentField] = NonEmptySet.of(ADocumentField("field1"), ADocumentField("field2"), NegatedDocumentField("_all"))
            rule.settings.fields should be(expectedFields)
          }
        )
      }
      "only one negated field is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    fields: "~field1"
              |
              |""".stripMargin,
          assertion = rule => {
            val expectedFields: NonEmptySet[DocumentField] = NonEmptySet.of(ADocumentField("field1"), NegatedDocumentField("_all"))
            rule.settings.fields should be(expectedFields)
          }
        )
      }
      "two negated fields are defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    fields: [~field1, ~field2]
              |
              |""".stripMargin,
          assertion = rule => {
            val expectedFields: NonEmptySet[DocumentField] = NonEmptySet.of(ADocumentField("field1"), ADocumentField("field2"), NegatedDocumentField("_all"))
            rule.settings.fields should be(expectedFields)
          }
        )
      }
    }
    "not be able to read from config" when {
      "no field is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    fields:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue(jsonFrom(
              """
                |[{
                |  "fields" : null
                |}]
              """.stripMargin))))
          }
        )
      }
      "negated fields are mixed with non-negated ones" in {
        assertDecodingFailure(
          yaml =
            """
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
              |  access_control_rules:
              |
              |  - name: test_block1
              |    fields: [field1, _type, _uid]
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message(
              "The fields rule cannot contain always-allowed fields: _routing,_size,_ttl,_parent,_index,_type,_id,_uid,_timestamp"
            )))
          }
        )
      }
    }
  }

}
