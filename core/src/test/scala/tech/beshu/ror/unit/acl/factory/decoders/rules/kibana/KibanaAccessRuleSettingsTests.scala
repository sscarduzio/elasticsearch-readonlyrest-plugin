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

import eu.timepit.refined.auto.*
import org.scalatest.matchers.should.Matchers.*
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaAccessRule
import tech.beshu.ror.accesscontrol.domain.{IndexName, KibanaAccess, RorConfigurationIndex}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.{BlocksLevelCreationError, RulesLevelCreationError}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.unit.acl.factory.decoders.rules.BaseRuleSettingsDecoderTest
import tech.beshu.ror.utils.TestsUtils.unsafeNes

class KibanaAccessRuleSettingsTests extends BaseRuleSettingsDecoderTest[KibanaAccessRule] {

  "A KibanaAccess" should {
    "be able to be loaded from config" when {
      "ro access is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana_access: ro
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.access should be(KibanaAccess.RO)
            rule.settings.rorIndex should be(RorConfigurationIndex(IndexName.Full(".readonlyrest")))
          }
        )
      }
      "rw access is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana_access: rw
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.access should be(KibanaAccess.RW)
            rule.settings.rorIndex should be(RorConfigurationIndex(IndexName.Full(".readonlyrest")))
          }
        )
      }
      "ro_strict access is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana_access: RO_STRICT
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.access should be(KibanaAccess.ROStrict)
            rule.settings.rorIndex should be(RorConfigurationIndex(IndexName.Full(".readonlyrest")))
          }
        )
      }
      "admin access is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana_access: admin
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.access should be(KibanaAccess.Admin)
            rule.settings.rorIndex should be(RorConfigurationIndex(IndexName.Full(".readonlyrest")))
          }
        )
      }
      "api_only access is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana_access: api_only
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.access should be(KibanaAccess.ApiOnly)
            rule.settings.rorIndex should be(RorConfigurationIndex(IndexName.Full(".readonlyrest")))
          }
        )
      }
      "unrestricted access is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana_access: unrestricted
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.access should be(KibanaAccess.Unrestricted)
            rule.settings.rorIndex should be(RorConfigurationIndex(IndexName.Full(".readonlyrest")))
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "no access is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana_access:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue.fromString(
              """kibana_access: null
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
                |    kibana_access: ro
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
                |    kibana_access: ro
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
                |    kibana_access: ro
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
                |    kibana_access: ro
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
