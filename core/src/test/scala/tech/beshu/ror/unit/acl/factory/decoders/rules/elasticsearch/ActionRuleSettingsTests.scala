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

import cats.data.NonEmptySet
import org.scalatest.matchers.should.Matchers._
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.ActionsRule
import tech.beshu.ror.accesscontrol.domain.Action
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.MalformedValue
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.unit.acl.factory.decoders.rules.BaseRuleSettingsDecoderTest

class ActionRuleSettingsTests extends BaseRuleSettingsDecoderTest[ActionsRule] {

  "An ActionRule" should {
    "be able to be loaded from config" when {
      "only one action is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    actions: "example_action"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.actions should be(NonEmptySet.one(Action("example_action")))
          }
        )
      }
      "several actions are defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    actions: [one, two, three]
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.actions should be(NonEmptySet.of(Action("one"), Action("two"), Action("three")))
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "no action is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    actions:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue(
              """actions: null
                |""".stripMargin
            )))
          }
        )
      }
    }
  }
}
