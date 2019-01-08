package tech.beshu.ror.acl.factory.decoders

import cats.data.NonEmptySet
import com.softwaremill.sttp.Method
import org.scalatest.Matchers._
import tech.beshu.ror.TestsUtils.jsonFrom
import tech.beshu.ror.acl.blocks.rules.MethodsRule
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.commons.orders._

class MethodsRuleSettingsTests extends RuleSettingsDecoderTest[MethodsRule] {

  "A MethodsRule" should {
    "be able to read from config" when {
      "one http method is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    methods: "GET"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.methods should be(NonEmptySet.one(Method.GET))
          }
        )
      }
      "several http methods are defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    methods: [GET, POST, PUT, DELETE, HEAD, OPTIONS]
              |
              |""".stripMargin,
          assertion = rule => {
            val headers = NonEmptySet.of(Method.GET, Method.POST, Method.PUT, Method.DELETE, Method.HEAD, Method.OPTIONS)
            rule.settings.methods should be(headers)
          }
        )
      }
    }
    "not be able to read from config" when {
      "no http method is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    methods:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue(jsonFrom(
              """
                |[{
                |  "methods" : null
                |}]
              """.stripMargin))))
          }
        )
      }
      "unknown http method is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    methods: NEW
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(Message("Unknown/unsupported http method: NEW")))
          }
        )
      }
    }
  }
}
