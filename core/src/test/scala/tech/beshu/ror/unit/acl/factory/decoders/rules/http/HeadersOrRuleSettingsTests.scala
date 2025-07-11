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

import cats.data.NonEmptySet
import org.scalatest.matchers.should.Matchers.*
import tech.beshu.ror.accesscontrol.blocks.rules.http.HeadersOrRule
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason.MalformedValue
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.orders.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.unit.acl.factory.decoders.rules.BaseRuleSettingsDecoderTest
import tech.beshu.ror.utils.TestsUtils.*

class HeadersOrRuleSettingsTests extends BaseRuleSettingsDecoderTest[HeadersOrRule] {

  "A HeadersOrRule" should {
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
              |    headers_or: "X-Some-Header:one"
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
              |    headers_or: ["X-Some-Header1:one", "X-Some-Header2:two"]
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
      "negation syntax is used" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    headers_or: ["~X-Some-Header1:one", "~X-Some-Header2:two"]
              |
              |""".stripMargin,
          assertion = rule => {
            val headers = NonEmptySet.of(
              forbiddenHeaderFrom("X-Some-Header1" -> "one"),
              forbiddenHeaderFrom("X-Some-Header2" -> "two")
            )
            rule.settings.headerAccessRequirements should be(headers)
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
            errors.head should be (RulesLevelCreationError(MalformedValue.fromString(
              """headers_or: null
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
              |    headers_or: ["~X-Some-Header1:one", ":two"]
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue.fromString(
              """|headers_or:
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
              |    headers_or: ["~X-Some-Header1:one", "~:two"]
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue.fromString(
              """headers_or:
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
              |    headers_or: ["~X-Some-Header1:one", "test:"]
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue.fromString(
              """headers_or:
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
              |    headers_or: ["~X-Some-Header1:one", "two"]
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue.fromString(
              """headers_or:
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
