package tech.beshu.ror.unit.acl.factory.decoders

import cats.data.NonEmptySet
import org.scalatest.Matchers._

import tech.beshu.ror.acl.blocks.Value._
import tech.beshu.ror.acl.blocks.Variable.ValueWithVariable
import tech.beshu.ror.acl.blocks.rules.HostsRule
import tech.beshu.ror.acl.blocks.{Const, Value, Variable}
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.MalformedValue
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.aDomain.Address
import tech.beshu.ror.acl.orders._

class HostsRuleSettingsTests extends RuleSettingsDecoderTest[HostsRule] {

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
            val addresses: NonEmptySet[Value[Address]] = NonEmptySet.one(Const(Address("192.168.0.1")))
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
            val addresses: NonEmptySet[Value[Address]] = NonEmptySet.one(Const(Address("192.168.0.1")))
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
            val addresses: NonEmptySet[Value[Address]] = NonEmptySet.one(Variable(ValueWithVariable("@{user}.com"), rv => Right(Address(rv.value))))
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
            val addresses: NonEmptySet[Value[Address]] = NonEmptySet.of(Const(Address("192.168.0.1")), Const(Address("192.168.0.2")))
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
              """readonlyrest:
                |  access_control_rules:
                |  - hosts: null
                |""".stripMargin
            )))
          }
        )
      }
    }
  }
}
