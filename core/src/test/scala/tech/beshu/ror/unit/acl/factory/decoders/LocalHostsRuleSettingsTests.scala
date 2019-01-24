package tech.beshu.ror.unit.acl.factory.decoders

import cats.data.NonEmptySet
import org.scalatest.Matchers._

import tech.beshu.ror.acl.blocks.Variable.ValueWithVariable
import tech.beshu.ror.acl.blocks.rules.LocalHostsRule
import tech.beshu.ror.acl.blocks.{Const, Value, Variable}
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.MalformedValue
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.aDomain.Address
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
            val addresses: NonEmptySet[Value[Address]] = NonEmptySet.one(Const(Address("192.168.0.1")))
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
            val addresses: NonEmptySet[Value[Address]] = NonEmptySet.one(Variable(ValueWithVariable("@{user}.com"), rv => Right(Address(rv.value))))
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
            val addresses: NonEmptySet[Value[Address]] = NonEmptySet.of(Const(Address("192.168.0.1")), Const(Address("192.168.0.2")))
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
