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
import eu.timepit.refined.auto._
import org.scalatest.matchers.should.Matchers._
import tech.beshu.ror.accesscontrol.blocks.rules.KibanaHideAppsRule
import tech.beshu.ror.accesscontrol.domain.KibanaApp
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.MalformedValue
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.orders._

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
            rule.settings.kibanaAppsToHide should be(NonEmptySet.one(KibanaApp("app1")))
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
              |    kibana_hide_apps: [app1, app2]
              |
              |""".stripMargin,
          assertion = rule => {
            val apps = NonEmptySet.of(KibanaApp("app1"), KibanaApp("app2"))
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
            errors.head should be (RulesLevelCreationError(MalformedValue(
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
            errors.head should be (RulesLevelCreationError(MalformedValue(
              """kibana_hide_apps: null
                |""".stripMargin)))
          }
        )
      }
    }
  }
}
