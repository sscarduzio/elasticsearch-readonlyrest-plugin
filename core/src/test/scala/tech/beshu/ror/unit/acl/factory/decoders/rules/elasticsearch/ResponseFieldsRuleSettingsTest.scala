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
package tech.beshu.ror.unit.acl.factory.decoders.rules.elasticsearch

import eu.timepit.refined.auto._
import org.scalatest.matchers.should.Matchers._
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.ResponseFieldsRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.{AlreadyResolved, ToBeResolved}
import tech.beshu.ror.accesscontrol.domain.ResponseFieldsFiltering._
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.unit.acl.factory.decoders.rules.BaseRuleSettingsDecoderTest
import tech.beshu.ror.utils.TestsUtils._

class ResponseFieldsRuleSettingsTest extends BaseRuleSettingsDecoderTest[ResponseFieldsRule] {

  "A ResponseFieldsRule" should {
    "be able to be loaded from config" when {
      "ror is run in plugin mode and" when {
        "only one field is defined" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    response_fields: "field1"
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.responseFields.head should be(AlreadyResolved(ResponseField("field1").nel))
              rule.settings.accessMode should be(AccessMode.Whitelist)
            }
          )
        }
        "two fields are defined" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    response_fields: [field1, field2]
                |
                |""".stripMargin,
            assertion = rule => {
              val decodedFields = rule.settings.responseFields
              decodedFields.head should be(AlreadyResolved(ResponseField("field1").nel))
              decodedFields.last should be(AlreadyResolved(ResponseField("field2").nel))
              rule.settings.accessMode should be(AccessMode.Whitelist)
            }
          )
        }
        "only one blacklisted field is defined" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    response_fields: "~field1"
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.responseFields.head should be(AlreadyResolved(ResponseField("field1").nel))
              rule.settings.accessMode should be(AccessMode.Blacklist)
            }
          )
        }
        "two blacklisted fields are defined" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    response_fields: [~field1, ~field2]
                |
                |""".stripMargin,
            assertion = rule => {
              val decodedFields = rule.settings.responseFields
              decodedFields.head should be(AlreadyResolved(ResponseField("field1").nel))
              decodedFields.last should be(AlreadyResolved(ResponseField("field2").nel))
              rule.settings.accessMode should be(AccessMode.Blacklist)
            }
          )
        }
        "field is defined with variable" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    auth_key: dev1:test
                |    response_fields: ["@{user}", "field2"]
                |
                |""".stripMargin,
            assertion = rule => {
              rule.settings.responseFields.head shouldBe a[ToBeResolved[_]]
              rule.settings.responseFields.last should be(AlreadyResolved(ResponseField("field2").nel))
              rule.settings.accessMode should be(AccessMode.Whitelist)
            }
          )
        }
        "fields disallowed by FLS are defined" in {
          assertDecodingSuccess(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    response_fields: [field1, _type, _uid]
                |
                |""".stripMargin,
            assertion = rule => {
              val decodedFields = rule.settings.responseFields
              decodedFields.head should be(AlreadyResolved(ResponseField("field1").nel))
              decodedFields.last should be(AlreadyResolved(ResponseField("_uid").nel))
              rule.settings.accessMode should be(AccessMode.Whitelist)
            }
          )
        }
      }
    }
    "not be able to be loaded from config" when {
      "no field is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    response_fields:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue(
              """response_fields: null
                |""".stripMargin
            )))
          }
        )
      }
      "blacklisted fields are mixed with whitelisted ones" when {
        "no variable is used" in {
          assertDecodingFailure(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    response_fields: [field1, ~field2]
                |
                |""".stripMargin,
            assertion = errors => {
              errors should have size 1
              errors.head should be(RulesLevelCreationError(Message(
                "fields should all be negated (i.e. '~field1') or all without negation (i.e. 'field1') Found: 'field1','~field2'"
              )))
            }
          )
        }
        "variable and already resolved value are used" in {
          assertDecodingFailure(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    auth_key: dev1:test
                |    response_fields: ["@{user}", "~field2"]
                |
                |""".stripMargin,
            assertion = errors => {
              errors should have size 1
              errors.head should be(RulesLevelCreationError(Message(
                "fields should all be negated (i.e. '~field1') or all without negation (i.e. 'field1') Found: '@{user}','~field2'"
              )))
            }
          )
        }
        "only variables are used" in {
          assertDecodingFailure(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block1
                |    auth_key: dev1:test
                |    response_fields: ["@{user}", "~@{user}.name"]
                |
                |""".stripMargin,
            assertion = errors => {
              errors should have size 1
              errors.head should be(RulesLevelCreationError(Message(
                "fields should all be negated (i.e. '~field1') or all without negation (i.e. 'field1') Found: '@{user}','~@{user}.name'"
              )))
            }
          )
        }
      }
    }
  }
}
