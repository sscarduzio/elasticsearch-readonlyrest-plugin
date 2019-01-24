package tech.beshu.ror.unit.acl.factory.decoders

import org.scalatest.Matchers._
import squants.information.Bytes

import tech.beshu.ror.acl.blocks.rules.MaxBodyLengthRule
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError

class MaxBodyLengthRuleSettingsTests extends BaseRuleSettingsDecoderTest[MaxBodyLengthRule] {

  "A MaxBodyLengthRule" should {
    "be able to be loaded from config" when {
      "max body length > 0 is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    max_body_length: 1000
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.maxContentLength should be(Bytes(1000))
          }
        )
      }
      "max body length == 0 is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    max_body_length: 0
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.maxContentLength should be(Bytes(0))
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "max body length field is defined, but no value it set" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    max_body_length:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue(
              """max_body_length: null
                |""".stripMargin
            )))
          }
        )
      }
      "max body length < 0 is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    max_body_length: -100
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(Message("Invalid max body length: -100")))
          }
        )
      }
    }
  }
}
