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
package tech.beshu.ror.unit.acl.factory.decoders.rules.http

import org.scalatest.matchers.should.Matchers.*
import squants.information.Bytes
import tech.beshu.ror.accesscontrol.blocks.rules.http.MaxBodyLengthRule
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.syntax.*
import tech.beshu.ror.unit.acl.factory.decoders.rules.BaseRuleSettingsDecoderTest

class MaxBodyLengthRuleSettingsTests extends BaseRuleSettingsDecoderTest[MaxBodyLengthRule] {

  "A MaxBodyLengthRule" should {
    "be able to be loaded from config" when {
      "max body length > 0 is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    max_body_length: 1000
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.maxContentLength should be(Bytes(1000))
          }
        )
      }
      "max body length == 0 is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    max_body_length: 0
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.maxContentLength should be(Bytes(0))
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "max body length field is defined, but no value it set" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    max_body_length:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue.fromString(
              """max_body_length: null
                |""".stripMargin
            )))
          }
        )
      }
      "max body length < 0 is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    max_body_length: -100
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(Message("Invalid max body length: -100")))
          }
        )
      }
    }
  }
}
