package tech.beshu.ror.unit.acl.factory.decoders

import cats.data.NonEmptySet
import org.scalatest.Matchers._
import tech.beshu.ror.TestsUtils._
import tech.beshu.ror.acl.blocks.rules.ApiKeysRule
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.MalformedValue
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.orders._

class ApiKeysRuleSettingsTests extends BaseRuleSettingsDecoderTest[ApiKeysRule] {

  "An ApiKeysRule" should {
    "be able to be loaded from config" when {
      "only one api key is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    api_keys: "example_api_key"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.apiKeys should be(NonEmptySet.one(apiKeyFrom("example_api_key")))
          }
        )
      }
      "several api keys are defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    api_keys: [one, two, three]
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.apiKeys should be(NonEmptySet.of(apiKeyFrom("one"), apiKeyFrom("two"), apiKeyFrom("three")))
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "no api key is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    api_keys:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue(
              """api_keys: null
                |""".stripMargin)))
          }
        )
      }
    }
  }
}
