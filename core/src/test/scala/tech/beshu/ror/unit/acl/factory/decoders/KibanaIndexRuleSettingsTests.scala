package tech.beshu.ror.unit.acl.factory.decoders

import org.scalatest.Matchers._
import tech.beshu.ror.TestsUtils.jsonFrom
import tech.beshu.ror.unit.acl.blocks.Variable.ValueWithVariable
import tech.beshu.ror.unit.acl.blocks.rules.KibanaIndexRule
import tech.beshu.ror.unit.acl.blocks.{Const, Variable}
import tech.beshu.ror.unit.acl.factory.RorAclFactory.AclCreationError.Reason.MalformedValue
import tech.beshu.ror.unit.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.commons.aDomain.IndexName

class KibanaIndexRuleSettingsTests extends RuleSettingsDecoderTest[KibanaIndexRule] {

  "A KibanaIndexRule" should {
    "be able to read from config" when {
      "kibana index is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana_index: some_kibana_index
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.kibanaIndex should be(Const(IndexName("some_kibana_index")))
          }
        )
      }
      "kibana index is defined with variable" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana_index: "@{user}_kibana_index"
              |
              |""".stripMargin,
          assertion = rule => {
            val variable = Variable(ValueWithVariable("@{user}_kibana_index"), rv => Right(IndexName(rv.value)))
            rule.settings.kibanaIndex shouldBe a [Variable[_]]
            rule.settings.kibanaIndex.asInstanceOf[Variable[IndexName]].representation should be(variable.representation)
          }
        )
      }
    }
    "not be able to read from config" when {
      "no kibana index is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana_index:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue(jsonFrom(
              """
                |[{
                |  "kibana_index" : null
                |}]
              """.stripMargin))))
          }
        )
      }
    }
  }
}
