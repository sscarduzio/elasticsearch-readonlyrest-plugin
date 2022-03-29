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
package tech.beshu.ror.unit.acl.factory.decoders

import cats.data.NonEmptySet
import com.softwaremill.sttp.Method
import org.scalatest.matchers.should.Matchers._

import tech.beshu.ror.accesscontrol.blocks.rules.MethodsRule
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.orders._

class MethodsRuleSettingsTests extends BaseRuleSettingsDecoderTest[MethodsRule] {

  "A MethodsRule" should {
    "be able to be loaded from config" when {
      "one http method is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
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
              |readonlyrest:
              |
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
    "not be able to be loaded from config" when {
      "no http method is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    methods:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue(
              """methods: null
                |""".stripMargin
            )))
          }
        )
      }
      "unknown http method is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
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
