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

import tech.beshu.ror.acl.domain.{IndexName, KibanaAccess}
import tech.beshu.ror.acl.blocks.Variable.ValueWithVariable
import tech.beshu.ror.acl.blocks.{Const, Variable}
import tech.beshu.ror.acl.blocks.rules.KibanaAccessRule
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.Reason.MalformedValue
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.RulesLevelCreationError

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
            rule.settings.kibanaIndex should be(Const(IndexName(".kibana")))
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
            rule.settings.kibanaIndex should be(Const(IndexName(".kibana")))
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
            rule.settings.kibanaIndex should be(Const(IndexName(".kibana")))
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
            rule.settings.kibanaIndex should be(Const(IndexName(".kibana")))
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
            rule.settings.kibanaIndex should be(Const(IndexName(".kibana_admin")))
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
            val variable = Variable(ValueWithVariable(".kibana_@{user}"), rv => Right(IndexName(rv.value)))
            rule.settings.kibanaIndex shouldBe a [Variable[_]]
            rule.settings.kibanaIndex.asInstanceOf[Variable[IndexName]].representation should be(variable.representation)
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
            rule.settings.kibanaIndex should be(Const(IndexName(".kibana")))
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
