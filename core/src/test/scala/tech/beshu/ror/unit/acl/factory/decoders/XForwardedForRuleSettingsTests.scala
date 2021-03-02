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
import org.scalatest.matchers.should.Matchers._
import tech.beshu.ror.accesscontrol.blocks.rules.XForwardedForRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.{AlreadyResolved, ToBeResolved}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.MalformedValue
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.domain.Address
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.utils.TestsUtils._

class XForwardedForRuleSettingsTests extends BaseRuleSettingsDecoderTest[XForwardedForRule] {

  "A XForwardedForRule" should {
    "be able to be loaded from config" when {
      "only one address is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    x_forwarded_for: "proxy1"
              |
              |""".stripMargin,
          assertion = rule => {
            val addresses: NonEmptySet[RuntimeMultiResolvableVariable[Address]] = NonEmptySet.one(AlreadyResolved(Address.from("proxy1").get.nel))
            rule.settings.allowedAddresses should be(addresses)
          }
        )
      }
      "two addresses are defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    x_forwarded_for: [proxy1, proxy2]
              |
              |""".stripMargin,
          assertion = rule => {
            val addresses: NonEmptySet[RuntimeMultiResolvableVariable[Address]] =
              NonEmptySet.of(AlreadyResolved(Address.from("proxy1").get.nel), AlreadyResolved(Address.from("proxy2").get.nel))
            rule.settings.allowedAddresses should be(addresses)
          }
        )
      }
      "two addresses are defined but one with variable" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key: user:pass
              |    x_forwarded_for: ["proxy1", "@{user}_proxy"]
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.allowedAddresses.length shouldBe 2
            rule.settings.allowedAddresses.head should be(AlreadyResolved(Address.from("proxy1").get.nel))
            rule.settings.allowedAddresses.tail.head shouldBe a [ToBeResolved[_]]
          }
        )
      }
      "only one IP is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    x_forwarded_for: "192.168.0.1"
              |
              |""".stripMargin,
          assertion = rule => {
            val ips: NonEmptySet[RuntimeMultiResolvableVariable[Address]] =
              NonEmptySet.of(AlreadyResolved(Address.from("192.168.0.1").get.nel))
            rule.settings.allowedAddresses should be(ips)
          }
        )
      }
      "two IPs are defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    x_forwarded_for: [192.168.0.1, 192.168.0.2]
              |
              |""".stripMargin,
          assertion = rule => {
            val ips: NonEmptySet[RuntimeMultiResolvableVariable[Address]] =
              NonEmptySet.of(AlreadyResolved(Address.from("192.168.0.1").get.nel), AlreadyResolved(Address.from("192.168.0.2").get.nel))
            rule.settings.allowedAddresses should be(ips)
          }
        )
      }
      "one address and one IP are defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    x_forwarded_for: [192.168.0.1, proxy1]
              |
              |""".stripMargin,
          assertion = rule => {
            val addresses: NonEmptySet[RuntimeMultiResolvableVariable[Address]] =
              NonEmptySet.of(AlreadyResolved(Address.from("192.168.0.1").get.nel), AlreadyResolved(Address.from("proxy1").get.nel))
            rule.settings.allowedAddresses should be(addresses)
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "no address or ip is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    x_forwarded_for:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue(
              """x_forwarded_for: null
                |""".stripMargin)))
          }
        )
      }
    }
  }

}