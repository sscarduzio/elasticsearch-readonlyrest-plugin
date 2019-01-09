package tech.beshu.ror.acl.factory.decoders

import cats.data.NonEmptySet
import org.scalatest.Matchers._
import tech.beshu.ror.TestsUtils.jsonFrom
import tech.beshu.ror.acl.blocks.Variable.ValueWithVariable
import tech.beshu.ror.acl.blocks.rules.UsersRule
import tech.beshu.ror.acl.blocks.{Const, Value, Variable}
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.MalformedValue
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.commons.domain.User
import tech.beshu.ror.commons.orders._

class UsersRuleSettingsTests extends RuleSettingsDecoderTest[UsersRule] {

  "A UsersRule" should {
    "be able to read from config" when {
      "only one user is defined" in {
        assertDecodingSuccess(
          yaml =
            """
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
    "not be able to read from config" when {
      "no user is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    users:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue(jsonFrom(
              """
                |[{
                |  "users" : null
                |}]
              """.stripMargin))))
          }
        )
      }
    }
  }
}
