package tech.beshu.ror.acl.factory.decoders

import cats.data.NonEmptySet
import org.scalatest.Matchers._
import tech.beshu.ror.acl.blocks.rules.ActionsRule
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.commons.aDomain.Action
import tech.beshu.ror.commons.orders._
import tech.beshu.ror.TestsUtils.jsonFrom

class ActionRuleConfigTests extends RuleSettingsDecoderTest[ActionsRule] {

  "An ActionRule" should {
    "be able to read from config" when {
      "only one action is defined" in {
        assertDecodingSuccess(
          yaml =
            """
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
    "not be able to read from config" when {
      "no action is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    actions:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue(jsonFrom(
              """
                |[{
                |  "actions" : null
                |}]
              """.stripMargin))))
          }
        )
      }
    }
  }
}
