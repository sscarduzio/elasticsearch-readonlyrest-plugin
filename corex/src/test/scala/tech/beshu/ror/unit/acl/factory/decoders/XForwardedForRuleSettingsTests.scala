package tech.beshu.ror.unit.acl.factory.decoders

import cats.data.NonEmptySet
import org.scalatest.Matchers._
import tech.beshu.ror.IPMask

import tech.beshu.ror.acl.blocks.Variable.ValueWithVariable
import tech.beshu.ror.acl.blocks.rules.XForwardedForRule
import tech.beshu.ror.acl.blocks.{Const, Value, Variable}
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.Reason.MalformedValue
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.aDomain.Address
import tech.beshu.ror.acl.orders._

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
            val addresses: NonEmptySet[Value[Address]] = NonEmptySet.one(Const(Address("proxy1")))
            rule.settings.allowedAddresses should be(addresses)
            rule.settings.allowedIps should be(Set.empty[IPMask])
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
            val addresses: NonEmptySet[Value[Address]] = NonEmptySet.of(Const(Address("proxy1")), Const(Address("proxy2")))
            rule.settings.allowedAddresses should be(addresses)
            rule.settings.allowedIps should be(Set.empty[IPMask])
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
              |    x_forwarded_for: ["proxy1", "@{user}_proxy"]
              |
              |""".stripMargin,
          assertion = rule => {
            val addresses: NonEmptySet[Value[Address]] = NonEmptySet.of(Const(Address("proxy1")), Variable(ValueWithVariable("@{user}_proxy"), rv => Right(Address(rv.value))))
            rule.settings.allowedAddresses should be(addresses)
            rule.settings.allowedIps should be(Set.empty[IPMask])
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
            val ips: NonEmptySet[IPMask] = NonEmptySet.of(IPMask.getIPMask("192.168.0.1"))
            rule.settings.allowedAddresses should be(Set.empty[IPMask])
            rule.settings.allowedIps should be (ips)
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
            val ips: NonEmptySet[IPMask] = NonEmptySet.of(IPMask.getIPMask("192.168.0.1"), IPMask.getIPMask("192.168.0.2"))
            rule.settings.allowedAddresses should be(Set.empty[IPMask])
            rule.settings.allowedIps should be (ips)
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
            val addresses: NonEmptySet[Value[Address]] = NonEmptySet.one(Const(Address("proxy1")))
            val ips: NonEmptySet[IPMask] = NonEmptySet.of(IPMask.getIPMask("192.168.0.1"))
            rule.settings.allowedAddresses should be(addresses)
            rule.settings.allowedIps should be (ips)
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