package tech.beshu.ror.unit.acl.factory.decoders

import cats.data.NonEmptySet
import org.scalatest.Matchers._
import tech.beshu.ror.TestsUtils.jsonFrom
import tech.beshu.ror.unit.acl.blocks.rules.ApiKeysRule
import tech.beshu.ror.unit.acl.factory.RorAclFactory.AclCreationError.Reason.MalformedValue
import tech.beshu.ror.unit.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.commons.aDomain.ApiKey
import tech.beshu.ror.commons.orders._

class ApiKeysRuleSettingsTests extends RuleSettingsDecoderTest[ApiKeysRule] {

  "An ApiKeysRule" should {
    "be able to read from config" when {
      "only one api key is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    api_keys: "example_api_key"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.apiKeys should be(NonEmptySet.one(ApiKey("example_api_key")))
          }
        )
      }
      "several api keys are defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    api_keys: [one, two, three]
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.apiKeys should be(NonEmptySet.of(ApiKey("one"), ApiKey("two"), ApiKey("three")))
          }
        )
      }
    }
    "not be able to read from config" when {
      "no api key is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    api_keys:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue(jsonFrom(
              """
                |[{
                |  "api_keys" : null
                |}]
              """.stripMargin))))
          }
        )
      }
    }
  }
}
