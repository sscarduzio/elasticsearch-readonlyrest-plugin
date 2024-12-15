/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.unit.acl.factory.decoders.rules.auth

import cats.data.NonEmptySet
import org.scalatest.matchers.should.Matchers.*
import tech.beshu.ror.accesscontrol.blocks.rules.http.ApiKeysRule
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.MalformedValue
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.orders.*
import tech.beshu.ror.unit.acl.factory.decoders.rules.BaseRuleSettingsDecoderTest
import tech.beshu.ror.utils.TestsUtils.*

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
            errors.head should be (RulesLevelCreationError(MalformedValue.fromString(
              """api_keys: null
                |""".stripMargin)))
          }
        )
      }
    }
  }
}
