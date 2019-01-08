package tech.beshu.ror.acl.factory.decoders

import cats.data.NonEmptySet
import org.scalatest.Matchers._
import tech.beshu.ror.TestsUtils.jsonFrom
import tech.beshu.ror.acl.blocks.rules.HeadersAndRule
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.MalformedValue
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.commons.aDomain.Header
import tech.beshu.ror.commons.aDomain.Header.Name
import tech.beshu.ror.commons.orders._

class HeadersAndRuleSettingsTests extends RuleSettingsDecoderTest[HeadersAndRule] {

  "A HeadersAndRule" should {
    "be able to read from config" when {
      "only one header is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    headers_and: "X-Some-Header:one"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.headers should be(NonEmptySet.one(Header(Name("X-Some-Header"), "one")))
          }
        )
      }
      "several headers are defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    headers_and: [X-Some-Header1:one, X-Some-Header2:two]
              |
              |""".stripMargin,
          assertion = rule => {
            val headers = NonEmptySet.of(Header(Name("X-Some-Header1"), "one"), Header(Name("X-Some-Header2"), "two"))
            rule.settings.headers should be(headers)
          }
        )
      }
    }
    "not be able to read from config" when {
      "no header is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    headers_and:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue(jsonFrom(
              """
                |[{
                |  "headers_and" : null
                |}]
              """.stripMargin))))
          }
        )
      }
    }
  }
}
