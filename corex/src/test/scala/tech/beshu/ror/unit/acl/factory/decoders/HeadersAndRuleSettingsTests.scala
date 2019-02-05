package tech.beshu.ror.unit.acl.factory.decoders

import cats.data.NonEmptySet
import org.scalatest.Matchers._
import tech.beshu.ror.TestsUtils._
import tech.beshu.ror.acl.blocks.rules.HeadersAndRule
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.MalformedValue
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.orders._

class HeadersAndRuleSettingsTests extends BaseRuleSettingsDecoderTest[HeadersAndRule] {

  "A HeadersAndRule" should {
    "be able to be loaded from config" when {
      "only one header is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    headers_and: "X-Some-Header:one"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.headers should be(NonEmptySet.one(headerFrom("X-Some-Header" -> "one")))
          }
        )
      }
      "several headers are defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    headers_and: [X-Some-Header1:one, X-Some-Header2:two]
              |
              |""".stripMargin,
          assertion = rule => {
            val headers = NonEmptySet.of(headerFrom("X-Some-Header1" -> "one"), headerFrom("X-Some-Header2" -> "two"))
            rule.settings.headers should be(headers)
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "no header is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    headers_and:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue(
              """headers_and: null
                |""".stripMargin
            )))
          }
        )
      }
    }
  }
}
