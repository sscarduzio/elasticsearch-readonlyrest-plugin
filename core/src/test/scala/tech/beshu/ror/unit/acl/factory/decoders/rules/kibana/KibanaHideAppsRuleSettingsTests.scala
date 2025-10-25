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

import org.scalatest.matchers.should.Matchers.*
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaHideAppsRule
import tech.beshu.ror.accesscontrol.domain.KibanaApp.FullNameKibanaApp
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.{BlocksLevelCreationError, RulesLevelCreationError}
import tech.beshu.ror.unit.acl.factory.decoders.rules.BaseRuleSettingsDecoderTest
import tech.beshu.ror.utils.TestsUtils.{kibanaAppRegex, unsafeNes}
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class KibanaHideAppsRuleSettingsTests extends BaseRuleSettingsDecoderTest[KibanaHideAppsRule] {

  "A KibanaHideAppsRule" should {
    "be able to be loaded from config" when {
      "only one kibana app is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana_hide_apps: "app1"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.kibanaAppsToHide should be(UniqueNonEmptyList.of(FullNameKibanaApp("app1")))
          }
        )
      }
      "several kibana apps are defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana_hide_apps: [app1, "/^Analytics\\|(?!(Maps)$).*$/"]
              |
              |""".stripMargin,
          assertion = rule => {
            val apps = UniqueNonEmptyList.of(
              FullNameKibanaApp("app1"),
              kibanaAppRegex("/^Analytics\\|(?!(Maps)$).*$/")
            )
            rule.settings.kibanaAppsToHide should be(apps)
          }
        )
      }
      "it's defined with 'actions' rule" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana_hide_apps: [app1, "/^Analytics\\|(?!(Maps)$).*$/"]
              |    actions: ["*"]
              |
              |""".stripMargin,
          assertion = rule => {
            val apps = UniqueNonEmptyList.of(
              FullNameKibanaApp("app1"),
              kibanaAppRegex("/^Analytics\\|(?!(Maps)$).*$/")
            )
            rule.settings.kibanaAppsToHide should be(apps)
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "empty string kibana app is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana_hide_apps: [""]
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue.fromString(
              """kibana_hide_apps:
                |- ""
                |""".stripMargin)))
          }
        )
      }
      "no kibana app is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana_hide_apps:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue.fromString(
              """kibana_hide_apps: null
                |""".stripMargin)))
          }
        )
      }
      "at least one kibana app regex cannot be compiled" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana_hide_apps: ["/^(?!(Analytics\\|Management).*$).*$/", "/^(?!(Analytics\\|Maps).*$.*$/"]
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue.fromString(
              """kibana_hide_apps:
                |- "/^(?!(Analytics\\|Management).*$).*$/"
                |- "/^(?!(Analytics\\|Maps).*$.*$/"
                |""".stripMargin)))
          }
        )
      }
      "it's defined with other rule in the block" when {
        "the rule is 'filter' rule" in {
          assertDecodingFailure(
            yaml =
              """
                |readonlyrest:
                |  access_control_rules:
                |
                |  - name: test_block
                |    kibana_hide_apps: "app1"
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
                |    kibana_hide_apps: "app1"
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
                |    kibana_hide_apps: "app1"
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
