package tech.beshu.ror.unit.acl.factory.decoders

import java.util.regex.Pattern

import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import tech.beshu.ror.TestsUtils.jsonFrom
import tech.beshu.ror.acl.blocks.Variable.ValueWithVariable
import tech.beshu.ror.acl.blocks.rules.UriRegexRule
import tech.beshu.ror.acl.blocks.{BlockContext, Variable, VariablesResolver}
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError

class UriRegexRuleSettingsTests extends RuleSettingsDecoderTest[UriRegexRule] with MockFactory {

  "A UriRegexRule" should {
    "be able to be loaded from config" when {
      "uri patten is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    uri_re: ^/secret-idx/.*
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.uriPattern.getValue(mock[VariablesResolver], mock[BlockContext]).map(_.pattern()) shouldBe Right("^/secret-idx/.*")
          }
        )
      }
      "uri patten is defined with variable" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    uri_re: "^/user/@{user}/.*"
              |
              |""".stripMargin,
          assertion = rule => {
            val variable = Variable(ValueWithVariable("^/user/@{user}/.*"), rv => Right(Pattern.compile(rv.value)))
            rule.settings.uriPattern shouldBe a [Variable[_]]
            rule.settings.uriPattern.asInstanceOf[Variable[Pattern]].representation should be(variable.representation)
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "no uri pattern is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    uri_re:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue(jsonFrom(
              """
                |[{
                |  "uri_re" : null
                |}]
              """.stripMargin))))
          }
        )
      }
      "pattern is malformed" in {
        assertDecodingFailure(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    uri_re: "abc["
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message("Cannot compile pattern: abc[")))
          }
        )
      }
    }
  }
}
