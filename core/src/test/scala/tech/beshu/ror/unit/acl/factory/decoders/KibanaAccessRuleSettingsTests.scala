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
import tech.beshu.ror.acl.blocks.rules.KibanaAccessRule
import tech.beshu.ror.acl.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.MalformedValue
import tech.beshu.ror.acl.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeSingleResolvableVariable.{AlreadyResolved, ToBeResolved}
import tech.beshu.ror.acl.domain.{IndexName, KibanaAccess}
import tech.beshu.ror.utils.TestsUtils.StringOps

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
            rule.settings.kibanaIndex should be(AlreadyResolved(IndexName(".kibana".nonempty)))
            rule.settings.kibanaMetadataEnabled should be (true)
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
            rule.settings.kibanaIndex should be(AlreadyResolved(IndexName(".kibana".nonempty)))
            rule.settings.kibanaMetadataEnabled should be (true)
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
            rule.settings.kibanaIndex should be(AlreadyResolved(IndexName(".kibana".nonempty)))
            rule.settings.kibanaMetadataEnabled should be (true)
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
            rule.settings.kibanaIndex should be(AlreadyResolved(IndexName(".kibana".nonempty)))
            rule.settings.kibanaMetadataEnabled should be (true)
          }
        )
      }
      "some access is defined with changed default kibana index" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana_access: admin
              |    kibana_index: ".kibana_admin"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.access should be(KibanaAccess.Admin)
            rule.settings.kibanaIndex should be(AlreadyResolved(IndexName(".kibana_admin".nonempty)))
            rule.settings.kibanaMetadataEnabled should be (true)
          }
        )
      }
      "some access is defined with changed default kibana index to variable" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana_access: admin
              |    kibana_index: .kibana_@{user}
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.access should be(KibanaAccess.Admin)
            rule.settings.kibanaIndex shouldBe a [ToBeResolved[_]]
            rule.settings.kibanaMetadataEnabled should be (true)
          }
        )
      }
      "some access is defined with disabled ror metadata" in {
        System.setProperty("com.readonlyrest.kibana.metadata", "false")
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
            rule.settings.kibanaIndex should be(AlreadyResolved(IndexName(".kibana".nonempty)))
            rule.settings.kibanaMetadataEnabled should be (false)
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
