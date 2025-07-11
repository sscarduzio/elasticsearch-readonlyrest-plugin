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
package tech.beshu.ror.unit.acl.factory.decoders.rules.transport

import cats.data.NonEmptySet
import org.scalatest.matchers.should.Matchers.*
import tech.beshu.ror.accesscontrol.blocks.rules.tranport.LocalHostsRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.{AlreadyResolved, ToBeResolved}
import tech.beshu.ror.accesscontrol.domain.Address
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason.MalformedValue
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.orders.*
import tech.beshu.ror.unit.acl.factory.decoders.rules.BaseRuleSettingsDecoderTest
import tech.beshu.ror.utils.TestsUtils.*

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
            val addresses: NonEmptySet[RuntimeMultiResolvableVariable[Address]] =
              NonEmptySet.one(AlreadyResolved(Address.from("192.168.0.1").get.nel))
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
              |    auth_key: user:pass
              |    hosts_local: "@{user}.com"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.allowedAddresses.length should be (1)
            rule.settings.allowedAddresses.head shouldBe a [ToBeResolved[_]]
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
            val addresses: NonEmptySet[RuntimeMultiResolvableVariable[Address]] =
              NonEmptySet.of(AlreadyResolved(Address.from("192.168.0.1").get.nel), AlreadyResolved(Address.from("192.168.0.2").get.nel))
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
            errors.head should be(RulesLevelCreationError(MalformedValue.fromString(
              """hosts_local: null
                |""".stripMargin)))
          }
        )
      }
    }
  }
}
