package tech.beshu.ror.unit.acl.factory.decoders

import org.scalatest.Matchers._
import tech.beshu.ror.TestsUtils.jsonFrom
import tech.beshu.ror.commons.aDomain.Filter
import tech.beshu.ror.unit.acl.blocks.Variable.ValueWithVariable
import tech.beshu.ror.unit.acl.blocks.rules.FilterRule
import tech.beshu.ror.unit.acl.blocks.{Const, Variable}
import tech.beshu.ror.unit.acl.factory.RorAclFactory.AclCreationError.Reason.MalformedValue
import tech.beshu.ror.unit.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError

class FilterRuleSettingsTests extends RuleSettingsDecoderTest[FilterRule] {

  "A FilterRule" should {
    "be able to read from config" when {
      "filter is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    filter: "{\"bool\":{\"must\":[{\"term\":{\"Country\":{\"value\":\"UK\"}}}]}}"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.filter should be(Const(Filter("{\"bool\":{\"must\":[{\"term\":{\"Country\":{\"value\":\"UK\"}}}]}}")))
          }
        )
      }
      "filter is defined with variable" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    filter: "{\"bool\":{\"must\":[{\"term\":{\"User\":{\"value\":\"@{user}\"}}}]}}"
              |
              |""".stripMargin,
          assertion = rule => {
            val variable = Variable(ValueWithVariable("{\"bool\":{\"must\":[{\"term\":{\"User\":{\"value\":\"@{user}\"}}}]}}"), rv => Right(Filter(rv.value)))
            rule.settings.filter shouldBe a [Variable[_]]
            rule.settings.filter.asInstanceOf[Variable[Filter]].representation should be(variable.representation)
          }
        )
      }
    }
    "not be able to read from config" when {
      "no filter is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    filter:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue(jsonFrom(
              """
                |[{
                |  "filter" : null
                |}]
              """.stripMargin))))
          }
        )
      }
    }
  }
}
