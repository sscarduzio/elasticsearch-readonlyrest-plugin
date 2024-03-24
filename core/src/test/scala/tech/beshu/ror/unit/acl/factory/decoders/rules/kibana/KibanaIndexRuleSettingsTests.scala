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
package tech.beshu.ror.unit.acl.factory.decoders.rules.kibana

import eu.timepit.refined.auto._
import org.scalatest.matchers.should.Matchers._
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaIndexRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeSingleResolvableVariable.{AlreadyResolved, ToBeResolved}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.{BlocksLevelCreationError, RulesLevelCreationError}
import tech.beshu.ror.unit.acl.factory.decoders.rules.BaseRuleSettingsDecoderTest
import tech.beshu.ror.utils.TestsUtils._

class KibanaIndexRuleSettingsTests extends BaseRuleSettingsDecoderTest[KibanaIndexRule] {

  "A KibanaIndexRule" should {
    "be able to be loaded from config" when {
      "kibana index is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana_index: some_kibana_index
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.kibanaIndex should be(AlreadyResolved(kibanaIndexName("some_kibana_index")))
          }
        )
      }
      "kibana index is defined with variable" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key: user:pass
              |    kibana_index: "@{user}_kibana_index"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.kibanaIndex shouldBe a [ToBeResolved[_]]
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "no kibana index is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana_index:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue(
              """kibana_index: null
                |""".stripMargin)))
          }
        )
      }
      "it's defined with other rule in the block" when {
        "the rule is 'actions' rule" in {
          assertDecodingFailure(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block
                |    kibana_index: some_kibana_index
                |    actions: ["indices:data/write/*"]
                |
                |""".stripMargin,
            assertion = errors => {
              errors should have size 1
              errors.head should be(BlocksLevelCreationError(Message(
                "The 'test_block' block contains 'kibana' rule (or any deprecated kibana-related rule) and 'actions' rule. These two cannot be used together in one block."
              )))
            }
          )
        }
        "the rule is 'filter' rule" in {
          assertDecodingFailure(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block
                |    kibana_index: some_kibana_index
                |    filter: "{\"bool\": {\"must\": [{\"term\": {\"title\": {\"value\": \"a1\"}}}]}}"
                |
                |""".stripMargin,
            assertion = errors => {
              errors should have size 1
              errors.head should be(BlocksLevelCreationError(Message(
                "The 'test_block' block contains 'kibana' rule (or any deprecated kibana-related rule) and 'filter' rule. These two cannot be used together in one block."
              )))
            }
          )
        }
        "the rule is 'fields' rule" in {
          assertDecodingFailure(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block
                |    kibana_index: some_kibana_index
                |    fields: ["_source","user1"]
                |
                |""".stripMargin,
            assertion = errors => {
              errors should have size 1
              errors.head should be(BlocksLevelCreationError(Message(
                "The 'test_block' block contains 'kibana' rule (or any deprecated kibana-related rule) and 'fields' rule. These two cannot be used together in one block."
              )))
            }
          )
        }
        "the rule is 'response_fields' rule" in {
          assertDecodingFailure(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block
                |    kibana_index: some_kibana_index
                |    response_fields: ["hits.hits"]
                |
                |""".stripMargin,
            assertion = errors => {
              errors should have size 1
              errors.head should be(BlocksLevelCreationError(Message(
                "The 'test_block' block contains 'kibana' rule (or any deprecated kibana-related rule) and 'response_fields' rule. These two cannot be used together in one block."
              )))
            }
          )
        }
      }
    }
  }
}
