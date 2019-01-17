package tech.beshu.ror.unit.acl.blocks.rules

import cats.data.NonEmptySet
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.acl.blocks.{BlockContext, Value}
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.rules.UsersRule
import tech.beshu.ror.commons.domain.User.Id
import tech.beshu.ror.commons.domain.{LoggedUser, User}
import tech.beshu.ror.commons.orders._
import tech.beshu.ror.mocks.MockRequestContext

class UsersRuleTests extends WordSpec with MockFactory {

  "An UsersRule" should {
    "match" when {
      "configured user id is the same as logged user id" in {
        assertMatchRule(
          configuredIds = NonEmptySet.of(userIdValueFrom("asd")),
          loggedUser = Some(LoggedUser(Id("asd")))
        )
      }
      "configured user id has wildcard and can be applied to logged user id" in {
        assertMatchRule(
          configuredIds = NonEmptySet.of(userIdValueFrom("as*")),
          loggedUser = Some(LoggedUser(Id("asd")))
        )
      }
    }
    "not match" when {
      "configured user id is different than logged user id" in {
        assertNotMatchRule(
          configuredIds = NonEmptySet.of(userIdValueFrom("_asd")),
          loggedUser = Some(LoggedUser(Id("asd")))
        )
      }
      "configured user id has wildcard but cannot be applied to logged user id" in {
        assertNotMatchRule(
          configuredIds = NonEmptySet.of(userIdValueFrom("as*")),
          loggedUser = Some(LoggedUser(Id("aXsd")))
        )
      }
      "user is not logged" in {
        assertNotMatchRule(
          configuredIds = NonEmptySet.of(userIdValueFrom("asd")),
          loggedUser = None
        )
      }
    }
  }

  private def assertMatchRule(configuredIds: NonEmptySet[Value[User.Id]], loggedUser: Option[LoggedUser]) =
    assertRule(configuredIds, loggedUser, isMatched = true)

  private def assertNotMatchRule(configuredIds: NonEmptySet[Value[User.Id]], loggedUser: Option[LoggedUser]) =
    assertRule(configuredIds, loggedUser, isMatched = false)

  private def assertRule(configuredIds: NonEmptySet[Value[User.Id]], loggedUser: Option[LoggedUser], isMatched: Boolean) = {
    val rule = new UsersRule(UsersRule.Settings(configuredIds))
    val requestContext = MockRequestContext.default
    val blockContext = mock[BlockContext]
    (blockContext.loggedUser _).expects().returning(loggedUser)
    rule.check(requestContext, blockContext).runSyncStep shouldBe Right {
      if (isMatched) Fulfilled(blockContext)
      else Rejected
    }
  }

  private def userIdValueFrom(value: String): Value[User.Id] = {
    Value
      .fromString(value, rv => Right(User.Id(rv.value)))
      .right
      .getOrElse(throw new IllegalStateException(s"Cannot create User Id Value from $value"))
  }
}
