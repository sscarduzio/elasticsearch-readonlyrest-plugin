package tech.beshu.ror.unit.acl.factory.decoders

import cats.data.NonEmptySet
import org.scalatest.Matchers._

import tech.beshu.ror.acl.aDomain.User
import tech.beshu.ror.acl.blocks.Variable.ValueWithVariable
import tech.beshu.ror.acl.blocks.rules.UsersRule
import tech.beshu.ror.acl.blocks.{Const, Value, Variable}
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.Reason.MalformedValue
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.orders._

class UsersRuleSettingsTests extends BaseRuleSettingsDecoderTest[UsersRule] {

  "A UsersRule" should {
    "be able to be loaded from config" when {
      "only one user is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    users: "user1"
              |
              |""".stripMargin,
          assertion = rule => {
            val userIds: NonEmptySet[Value[User.Id]] = NonEmptySet.one(Const(User.Id("user1")))
            rule.settings.userIds should be(userIds)
          }
        )
      }
      "only one user is defined with variable" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    users: "@{user}"
              |
              |""".stripMargin,
          assertion = rule => {
            val userIds: NonEmptySet[Value[User.Id]] = NonEmptySet.one(Variable(ValueWithVariable("@{user}"), rv => Right(User.Id(rv.value))))
            rule.settings.userIds should be(userIds)
          }
        )
      }
      "several users are defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    users: ["user1", "user2"]
              |
              |""".stripMargin,
          assertion = rule => {
            val userIds: NonEmptySet[Value[User.Id]] = NonEmptySet.of(Const(User.Id("user1")), Const(User.Id("user2")))
            rule.settings.userIds should be(userIds)
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "no user is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    users:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue(
              """users: null
                |""".stripMargin)))
          }
        )
      }
    }
  }
}
