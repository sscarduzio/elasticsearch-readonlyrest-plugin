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
import org.scalatest.matchers.should.Matchers._
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.accesscontrol.blocks.rules.HeadersAndRule
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.MalformedValue
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.orders._

class HeadersAndRuleSettingsTests extends BaseRuleSettingsDecoderTest[HeadersAndRule] {

  "A HeadersAndRule" should {
    "be able to be loaded from config" when {
      "only one header requirement is defined" in {
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
            rule.settings.headerAccessRequirements should be(NonEmptySet.one(
              requiredHeaderFrom("X-Some-Header" -> "one")
            ))
          }
        )
      }
      "several header requirements are defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    headers_and: ["X-Some-Header1:one", "X-Some-Header2:two"]
              |
              |""".stripMargin,
          assertion = rule => {
            val headers = NonEmptySet.of(
              requiredHeaderFrom("X-Some-Header1" -> "one"),
              requiredHeaderFrom("X-Some-Header2" -> "two")
            )
            rule.settings.headerAccessRequirements should be(headers)
          }
        )
      }
      "deprecated rule name is used" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    headers: "X-Some-Header:one"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.headerAccessRequirements should be(NonEmptySet.one(
              requiredHeaderFrom("X-Some-Header" -> "one")
            ))
          }
        )
      }
      "negation syntax is used" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    headers_and: ["~X-Some-Header1:one", "~X-Some-Header2:two"]
              |
              |""".stripMargin,
          assertion = rule => {
            val headers = NonEmptySet.of(
              forbiddenHeaderFrom("X-Some-Header1" -> "one"),
              forbiddenHeaderFrom("X-Some-Header2" -> "two")
            )
            rule.settings.headerAccessRequirements should be (headers)
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
      "header requirement name is empty" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    headers_and: ["~X-Some-Header1:one", ":two"]
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue(
              """|headers_and:
                 |- "~X-Some-Header1:one"
                 |- ":two"
                 |""".stripMargin
            )))
          }
        )
      }
      "header requirement negation is used without a name" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    headers_and: ["~X-Some-Header1:one", "~:two"]
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue(
              """headers_and:
                |- "~X-Some-Header1:one"
                |- "~:two"
                |""".stripMargin
            )))
          }
        )
      }
      "header requirement has no value" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    headers_and: ["~X-Some-Header1:one", "test:"]
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue(
              """headers_and:
                |- "~X-Some-Header1:one"
                |- "test:"
                |""".stripMargin
            )))
          }
        )
      }
      "header requirement has malformed syntax" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    headers_and: ["~X-Some-Header1:one", "two"]
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue(
              """headers_and:
                |- "~X-Some-Header1:one"
                |- "two"
                |""".stripMargin
            )))
          }
        )
      }
    }
  }
}
