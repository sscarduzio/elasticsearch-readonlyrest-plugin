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

import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.UriRegexRule
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeSingleResolvableVariable.ToBeResolved
import tech.beshu.ror.acl.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.acl.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.request.RequestContext

class UriRegexRuleSettingsTests extends BaseRuleSettingsDecoderTest[UriRegexRule] with MockFactory {

  "A UriRegexRule" should {
    "be able to be loaded from config" when {
      "uri patten is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    uri_re: ^/secret-idx/.*
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.uriPattern.resolve(mock[RequestContext], mock[BlockContext]).map(_.pattern()) shouldBe Right("^/secret-idx/.*")
          }
        )
      }
      "uri patten is defined with variable" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    uri_re: "^/user/@{user}/.*"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.uriPattern shouldBe a [ToBeResolved[_]]
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "no uri pattern is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    uri_re:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue(
              """uri_re: null
                |""".stripMargin
            )))
          }
        )
      }
      "pattern is malformed" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    uri_re: "abc["
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message("Cannot compile pattern: abc[")))
          }
        )
      }
    }
  }
}
