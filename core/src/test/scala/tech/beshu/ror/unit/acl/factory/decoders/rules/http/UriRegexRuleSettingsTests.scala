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
import cats.implicits._
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers._
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.http.UriRegexRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.ToBeResolved
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.unit.acl.factory.decoders.rules.BaseRuleSettingsDecoderTest

class UriRegexRuleSettingsTests extends BaseRuleSettingsDecoderTest[UriRegexRule] with MockFactory {

  "A UriRegexRule" should {
    "be able to be loaded from config" when {
      "single uri pattern is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    uri_re: "^/secret-idx/.*"
              |
              |""".stripMargin,
          assertion = rule => {
            val resolvedPatten = rule.settings
              .uriPatterns.head
              .resolve(CurrentUserMetadataRequestBlockContext(mock[RequestContext], UserMetadata.empty, Set.empty, List.empty))
              .map(_.head.pattern())

            resolvedPatten shouldBe Right("^/secret-idx/.*")
          }
        )
      }
      "rule is defined as list of patterns" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    uri_re: ["^/secret-idx/.*", "^/secret/.*"]
              |
              |""".stripMargin,
          assertion = rule => {
            val patternsAsStrings = rule
              .settings.uriPatterns
              .map(_
                .resolve(CurrentUserMetadataRequestBlockContext(mock[RequestContext], UserMetadata.empty, Set.empty, List.empty))
                .map(_.head.pattern)
                .toOption.get
              )
            patternsAsStrings shouldBe NonEmptySet.of("^/secret-idx/.*", "^/secret/.*")
          }
        )
      }
      "uri pattern is defined with variable" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key: user:pass
              |    uri_re: "^/user/@{user}/.*"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.uriPatterns.head shouldBe a[ToBeResolved[_]]
          }
        )
      }
      "uri pattern is defined with multi variable" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key: user:pass
              |    uri_re: ["^/user/@explode{user}/.*"]
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.uriPatterns.head shouldBe a[ToBeResolved[_]]
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
            errors.head should be(RulesLevelCreationError(MalformedValue.fromString(
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

      "some of patterns present in list is malformed" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    uri_re: ["^/secret-idx/.*", "abc["]
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
