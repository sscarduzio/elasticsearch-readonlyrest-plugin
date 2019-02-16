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
import tech.beshu.ror.acl.aDomain.Address
import tech.beshu.ror.acl.blocks.Value._
import tech.beshu.ror.acl.blocks.Variable.ValueWithVariable
import tech.beshu.ror.acl.blocks.rules.HostsRule
import tech.beshu.ror.acl.blocks.{Const, Value, Variable}
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.Reason.MalformedValue
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.orders._

class HostsRuleSettingsTests extends BaseRuleSettingsDecoderTest[HostsRule] {

  "A HostsRule" should {
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
              |    hosts: 192.168.0.1
              |
              |""".stripMargin,
          assertion = rule => {
            val addresses: NonEmptySet[Value[Address]] = NonEmptySet.one(Const(Address.from("192.168.0.1").get))
            rule.settings.allowedHosts should be(addresses)
            rule.settings.acceptXForwardedForHeader should be(false)
          }
        )
      }
      "only one host is defined with overwritten X-Forwarded-For header" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    hosts: "192.168.0.1"
              |    accept_x-forwarded-for_header: true
              |
              |""".stripMargin,
          assertion = rule => {
            val addresses: NonEmptySet[Value[Address]] = NonEmptySet.one(Const(Address.from("192.168.0.1").get))
            rule.settings.allowedHosts should be(addresses)
            rule.settings.acceptXForwardedForHeader should be(true)
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
              |    hosts: "@{user}.com"
              |
              |""".stripMargin,
          assertion = rule => {
            val addresses: NonEmptySet[Value[Address]] = NonEmptySet.one(Variable(ValueWithVariable("@{user}.com"), rv => Right(Address.from(rv.value).get)))
            rule.settings.allowedHosts should be(addresses)
            rule.settings.acceptXForwardedForHeader should be(false)
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
              |    hosts: ["192.168.0.1", "192.168.0.2"]
              |
              |""".stripMargin,
          assertion = rule => {
            val addresses: NonEmptySet[Value[Address]] = NonEmptySet.of(Const(Address.from("192.168.0.1").get), Const(Address.from("192.168.0.2").get))
            rule.settings.allowedHosts should be(addresses)
            rule.settings.acceptXForwardedForHeader should be(false)
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
              |    hosts:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue(
              """hosts: null
                |""".stripMargin
            )))
          }
        )
      }
    }
  }
}
