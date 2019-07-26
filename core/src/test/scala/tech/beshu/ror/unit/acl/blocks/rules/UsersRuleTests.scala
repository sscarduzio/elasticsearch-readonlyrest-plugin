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
package tech.beshu.ror.unit.acl.blocks.rules

import cats.data.NonEmptySet
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.rules.UsersRule
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.AlwaysRightConvertible
import tech.beshu.ror.acl.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeResolvableVariableCreator}
import tech.beshu.ror.acl.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.acl.domain.User.Id
import tech.beshu.ror.acl.domain.User
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils._

class UsersRuleTests extends WordSpec with MockFactory {

  "An UsersRule" should {
    "match" when {
      "configured user id is the same as logged user id" in {
        assertMatchRule(
          configuredIds = NonEmptySet.of(userIdValueFrom("asd")),
          loggedUser = Some(DirectlyLoggedUser(Id("asd".nonempty)))
        )
      }
      "configured user id has wildcard and can be applied to logged user id" in {
        assertMatchRule(
          configuredIds = NonEmptySet.of(userIdValueFrom("as*")),
          loggedUser = Some(DirectlyLoggedUser(Id("asd".nonempty)))
        )
      }
    }
    "not match" when {
      "configured user id is different than logged user id" in {
        assertNotMatchRule(
          configuredIds = NonEmptySet.of(userIdValueFrom("_asd")),
          loggedUser = Some(DirectlyLoggedUser(Id("asd".nonempty)))
        )
      }
      "configured user id has wildcard but cannot be applied to logged user id" in {
        assertNotMatchRule(
          configuredIds = NonEmptySet.of(userIdValueFrom("as*")),
          loggedUser = Some(DirectlyLoggedUser(Id("aXsd".nonempty)))
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

  private def assertMatchRule(configuredIds: NonEmptySet[RuntimeMultiResolvableVariable[User.Id]], loggedUser: Option[DirectlyLoggedUser]) =
    assertRule(configuredIds, loggedUser, isMatched = true)

  private def assertNotMatchRule(configuredIds: NonEmptySet[RuntimeMultiResolvableVariable[User.Id]], loggedUser: Option[DirectlyLoggedUser]) =
    assertRule(configuredIds, loggedUser, isMatched = false)

  private def assertRule(configuredIds: NonEmptySet[RuntimeMultiResolvableVariable[User.Id]], loggedUser: Option[DirectlyLoggedUser], isMatched: Boolean) = {
    val rule = new UsersRule(UsersRule.Settings(configuredIds))
    val requestContext = MockRequestContext.default
    val blockContext = mock[BlockContext]
    (blockContext.loggedUser _).expects().returning(loggedUser)
    rule.check(requestContext, blockContext).runSyncStep shouldBe Right {
      if (isMatched) Fulfilled(blockContext)
      else Rejected()
    }
  }

  private def userIdValueFrom(value: String): RuntimeMultiResolvableVariable[User.Id] = {
    RuntimeResolvableVariableCreator
      .createMultiResolvableVariableFrom(value.nonempty)(AlwaysRightConvertible.from(User.Id.apply))
      .right
      .getOrElse(throw new IllegalStateException(s"Cannot create User Id Value from $value"))
  }
}
