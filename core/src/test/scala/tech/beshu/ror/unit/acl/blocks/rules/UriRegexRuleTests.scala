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

import java.util.regex.Pattern

import cats.data.NonEmptySet
import cats.implicits._
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.UriRegexRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.ConvertError
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeResolvableVariableCreator, RuntimeSingleResolvableVariable}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.UriPath
import tech.beshu.ror.accesscontrol.domain.User.Id
import tech.beshu.ror.accesscontrol.orders.patternOrder
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils._

import scala.util.Try

class UriRegexRuleTests extends WordSpec with MockFactory {
  private val blockContext = mock[BlockContext]

  "An UriRegexRule" should {
    "match" when {
      "configured pattern matches uri from request" in {
        assertMatchRule(
          uriRegex = patternValueFrom(NonEmptySet.of("""^\/\d\d\w$""")),
          uriPath = UriPath("/123")
        )
      }
      "second configured pattern on list matches uri from request" in {
        assertMatchRule(
          uriRegex = patternValueFrom(NonEmptySet.of("""^\/\d$""", """^\/\d\d\d$""")),
          uriPath = UriPath("/123")
        )
      }
      "configured pattern with variable matches uri from request when user is logged" in {
        mockLoggedUser(Some("mia"))
        assertMatchRule(
          uriRegex = patternValueFrom(NonEmptySet.of("""^\/@{user}$""")),
          uriPath = UriPath("/mia")
        )
      }
      "configured pattern with variable containing namespace matches uri from request when user is logged" in {
        mockLoggedUser(Some("mia"))
        assertMatchRule(
          uriRegex = patternValueFrom(NonEmptySet.of("""^\/@{acl:user}$""")),
          uriPath = UriPath("/mia")
        )
      }
      "second configured pattern with variable matches uri from request when user is logged" in {
        mockLoggedUser(Some("mia"))
        assertMatchRule(
          uriRegex = patternValueFrom(NonEmptySet.of("""^\/mi\d$""", """^\/@{user}$""")),
          uriPath = UriPath("/mia")
        )
      }
    }
    "not matched" when {
      "configured pattern doesn't match uri from request" in {
        assertNotMatchRule(
          uriRegex = patternValueFrom(NonEmptySet.of("""\/\d\d\d$""")),
          uriPath = UriPath("/one")
        )
      }
      "none of configured patterns matches uri from request" in {
        assertNotMatchRule(
          uriRegex = patternValueFrom(NonEmptySet.of("""\/\d\d\d$""", """\/\d$""", """\/\w\w\d$""")),
          uriPath = UriPath("/one")
        )
      }
      "configured pattern with variable doesn't match uri from request when user is not logged" in {
        mockLoggedUser(None)
        assertNotMatchRule(
          uriRegex = patternValueFrom(NonEmptySet.of("""^\/@{user}$""")),
          uriPath = UriPath("/mia")
        )
      }
      "configured pattern with variable isn't able to compile to pattern after resolve" in {
        mockLoggedUser(Some("["))
        assertNotMatchRule(
          uriRegex = patternValueFrom(NonEmptySet.of("""^\/@{user}$""")),
          uriPath = UriPath("/mia")
        )
      }
    }
  }

  private def assertMatchRule(uriRegex: NonEmptySet[RuntimeSingleResolvableVariable[Pattern]], uriPath: UriPath) =
    assertRule(uriRegex, uriPath, isMatched = true)

  private def assertNotMatchRule(uriRegex: NonEmptySet[RuntimeSingleResolvableVariable[Pattern]], uriPath: UriPath) =
    assertRule(uriRegex, uriPath, isMatched = false)

  private def assertRule(uriRegex: NonEmptySet[RuntimeSingleResolvableVariable[Pattern]], uriPath: UriPath, isMatched: Boolean) = {
    val rule = new UriRegexRule(UriRegexRule.Settings(uriRegex))
    val requestContext = MockRequestContext(uriPath = uriPath)
    rule.check(requestContext, blockContext).runSyncStep shouldBe Right {
      if (isMatched) Fulfilled(blockContext)
      else Rejected()
    }
  }

  private def patternValueFrom(values: NonEmptySet[String]): NonEmptySet[RuntimeSingleResolvableVariable[Pattern]] = {
    values
      .map { value =>
        implicit val patternConvertible: Convertible[Pattern] = new Convertible[Pattern] {
          override def convert: String => Either[Convertible.ConvertError, Pattern] = str => {
            Try(Pattern.compile(str)).toEither.left.map(_ => ConvertError("msg"))
          }
        }
        RuntimeResolvableVariableCreator
          .createSingleResolvableVariableFrom[Pattern](value.nonempty)
          .right
          .getOrElse(throw new IllegalStateException(s"Cannot create Pattern Value from $value"))
      }
  }

  private def mockLoggedUser(name: Option[String]) = {
    (blockContext.loggedUser _).expects().returning(name.map(value => DirectlyLoggedUser(Id(value.nonempty))))
  }
}
