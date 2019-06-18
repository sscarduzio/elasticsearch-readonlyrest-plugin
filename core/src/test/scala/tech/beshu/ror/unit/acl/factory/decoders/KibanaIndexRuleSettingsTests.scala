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

import org.scalatest.Matchers._

import tech.beshu.ror.acl.blocks.Variable.ValueWithVariable
import tech.beshu.ror.acl.blocks.rules.KibanaIndexRule
import tech.beshu.ror.acl.blocks.{Const, Variable}
import tech.beshu.ror.acl.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.MalformedValue
import tech.beshu.ror.acl.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.domain.IndexName

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
            rule.settings.kibanaIndex should be(Const(IndexName("some_kibana_index")))
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
              |    kibana_index: "@{user}_kibana_index"
              |
              |""".stripMargin,
          assertion = rule => {
            val variable = Variable(ValueWithVariable("@{user}_kibana_index"), rv => Right(IndexName(rv.value)))
            rule.settings.kibanaIndex shouldBe a [Variable[_]]
            rule.settings.kibanaIndex.asInstanceOf[Variable[IndexName]].representation should be(variable.representation)
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
    }
  }
}
