package tech.beshu.ror.unit.acl.factory.decoders

import cats.data.NonEmptySet
import org.scalatest.Matchers._

import tech.beshu.ror.acl.blocks.rules.HeadersOrRule
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.MalformedValue
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.aDomain.Header
import tech.beshu.ror.acl.aDomain.Header.Name
import tech.beshu.ror.acl.orders._

class HeadersOrRuleSettingsTests extends BaseRuleSettingsDecoderTest[HeadersOrRule] {

  "A HeadersOrRule" should {
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
              |    headers_or: "X-Some-Header:one"
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
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    headers_or: [X-Some-Header1:one, X-Some-Header2:two]
              |
              |""".stripMargin,
          assertion = rule => {
            val headers = NonEmptySet.of(Header(Name("X-Some-Header1"), "one"), Header(Name("X-Some-Header2"), "two"))
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
              |    headers_or:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue(
              """headers_or: null
                |""".stripMargin
            )))
          }
        )
      }
    }
  }
}
