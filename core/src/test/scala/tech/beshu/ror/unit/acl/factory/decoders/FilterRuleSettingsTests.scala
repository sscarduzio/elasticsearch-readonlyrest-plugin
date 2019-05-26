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

import tech.beshu.ror.acl.domain.Filter
import tech.beshu.ror.acl.blocks.Variable.ValueWithVariable
import tech.beshu.ror.acl.blocks.rules.FilterRule
import tech.beshu.ror.acl.blocks.{Const, Variable}
import tech.beshu.ror.acl.factory.CirceCoreFactory.AclCreationError.Reason.MalformedValue
import tech.beshu.ror.acl.factory.CirceCoreFactory.AclCreationError.RulesLevelCreationError

class FilterRuleSettingsTests extends BaseRuleSettingsDecoderTest[FilterRule] {

  "A FilterRule" should {
    "be able to be loaded from config" when {
      "filter is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    filter: "{\"bool\":{\"must\":[{\"term\":{\"Country\":{\"value\":\"UK\"}}}]}}"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.filter should be(Const(Filter("{\"bool\":{\"must\":[{\"term\":{\"Country\":{\"value\":\"UK\"}}}]}}")))
          }
        )
      }
      "filter is defined with variable" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    filter: "{\"bool\":{\"must\":[{\"term\":{\"User\":{\"value\":\"@{user}\"}}}]}}"
              |
              |""".stripMargin,
          assertion = rule => {
            val variable = Variable(ValueWithVariable("{\"bool\":{\"must\":[{\"term\":{\"User\":{\"value\":\"@{user}\"}}}]}}"), rv => Right(Filter(rv.value)))
            rule.settings.filter shouldBe a [Variable[_]]
            rule.settings.filter.asInstanceOf[Variable[Filter]].representation should be(variable.representation)
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "no filter is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    filter:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue(
              """filter: null
                |""".stripMargin)))
          }
        )
      }
    }
  }
}
