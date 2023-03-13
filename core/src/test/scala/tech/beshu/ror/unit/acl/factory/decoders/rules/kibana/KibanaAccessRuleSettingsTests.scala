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
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers._
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaAccessRule
import tech.beshu.ror.accesscontrol.domain.{IndexName, KibanaAccess, RorConfigurationIndex}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.MalformedValue
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.unit.acl.factory.decoders.rules.BaseRuleSettingsDecoderTest

class KibanaAccessRuleSettingsTests extends BaseRuleSettingsDecoderTest[KibanaAccessRule] with MockFactory {

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
            errors.head should be(RulesLevelCreationError(MalformedValue(
              """kibana_access: null
                |""".stripMargin)))
          }
        )
      }
    }
  }
}
