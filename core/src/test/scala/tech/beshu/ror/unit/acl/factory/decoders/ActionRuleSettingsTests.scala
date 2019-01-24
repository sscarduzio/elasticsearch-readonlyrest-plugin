package tech.beshu.ror.unit.acl.factory.decoders

import cats.data.NonEmptySet
import org.scalatest.Matchers._
import tech.beshu.ror.acl.blocks.rules.ActionsRule
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.MalformedValue
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.aDomain.Action
import tech.beshu.ror.acl.orders._


class ActionRuleSettingsTests extends BaseRuleSettingsDecoderTest[ActionsRule] {

  "An ActionRule" should {
    "be able to be loaded from config" when {
      "only one action is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    actions: "example_action"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.actions should be(NonEmptySet.one(Action("example_action")))
          }
        )
      }
      "several actions are defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    actions: [one, two, three]
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.actions should be(NonEmptySet.of(Action("one"), Action("two"), Action("three")))
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "no action is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    actions:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue(
              """actions: null
                |""".stripMargin
            )))
          }
        )
      }
    }
  }
}
