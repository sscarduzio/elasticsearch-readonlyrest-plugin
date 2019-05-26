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
import org.scalatest.Matchers._

import tech.beshu.ror.acl.blocks.Variable.ValueWithVariable
import tech.beshu.ror.acl.blocks.rules.LocalHostsRule
import tech.beshu.ror.acl.blocks.{Const, Value, Variable}
import tech.beshu.ror.acl.factory.CirceCoreFactory.AclCreationError.Reason.MalformedValue
import tech.beshu.ror.acl.factory.CirceCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.domain.Address
import tech.beshu.ror.acl.orders._

class LocalHostsRuleSettingsTests extends BaseRuleSettingsDecoderTest[LocalHostsRule] {

  "A LocalHostsRule" should {
    "be able to be loaded from config" when {
      "only one host is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    hosts_local: 192.168.0.1
              |
              |""".stripMargin,
          assertion = rule => {
            val addresses: NonEmptySet[Value[Address]] = NonEmptySet.one(Const(Address.from("192.168.0.1").get))
            rule.settings.allowedAddresses should be(addresses)
          }
        )
      }
      "only one host is defined with variable" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    hosts_local: "@{user}.com"
              |
              |""".stripMargin,
          assertion = rule => {
            val addresses: NonEmptySet[Value[Address]] = NonEmptySet.one(Variable(ValueWithVariable("@{user}.com"), rv => Right(Address.from(rv.value).get)))
            rule.settings.allowedAddresses should be(addresses)
          }
        )
      }
      "several hosts are defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    hosts_local: ["192.168.0.1", "192.168.0.2"]
              |
              |""".stripMargin,
          assertion = rule => {
            val addresses: NonEmptySet[Value[Address]] = NonEmptySet.of(Const(Address.from("192.168.0.1").get), Const(Address.from("192.168.0.2").get))
            rule.settings.allowedAddresses should be(addresses)
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "no host is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    hosts_local:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue(
              """hosts_local: null
                |""".stripMargin)))
          }
        )
      }
    }
  }
}
