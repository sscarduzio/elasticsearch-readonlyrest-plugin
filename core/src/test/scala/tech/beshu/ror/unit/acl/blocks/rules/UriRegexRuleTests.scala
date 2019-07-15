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

import eu.timepit.refined.types.string.NonEmptyString
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.rules.UriRegexRule
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeResolvableVariable.Convertible
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.ConvertError
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeSingleResolvableVariable.{AlreadyResolved, ToBeResolved}
import tech.beshu.ror.acl.blocks.variables.runtime.{RuntimeResolvableVariableCreator, RuntimeSingleResolvableVariable}
import tech.beshu.ror.acl.domain.User.Id
import tech.beshu.ror.acl.domain.{LoggedUser, UriPath}
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils._

import scala.util.Try

class UriRegexRuleTests extends WordSpec with MockFactory {

  "An UriRegexRule" should {
    "match" when {
      "configured pattern matches uri from request" in {
        assertMatchRule(
          uriRegex = patternValueFrom("""^\/\d\d\d$"""),
          uriPath = UriPath("/123"),
          isUserLogged = false
        )
      }
      "configured pattern with variable matches uri from request when user is logged" in {
        assertMatchRule(
          uriRegex = patternValueFrom("""^\/@{user}$"""),
          uriPath = UriPath("/mia"),
          isUserLogged = true
        )
      }
    }
    "not matched" when {
      "configured pattern doesn't match uri from request" in {
        assertNotMatchRule(
          uriRegex = patternValueFrom("""\/\d\d\d$"""),
          uriPath = UriPath("/one"),
          isUserLogged = false
        )
      }
      "configured pattern with variable doesn't match uri from request when user is not logged" in {
        assertNotMatchRule(
          uriRegex = patternValueFrom("""^\/@{user}$"""),
          uriPath = UriPath("/mia"),
          isUserLogged = false
        )
      }
      "configured pattern with variable isn't able to compile to pattern after resolve" in {
        assertNotMatchRule(
          uriRegex = patternValueFrom("""^\/@{user}$"""),
          uriPath = UriPath("/mia"),
          isUserLogged = true,
          userName = "[".nonempty
        )
      }
    }
  }

  private def assertMatchRule(uriRegex: RuntimeSingleResolvableVariable[Pattern], uriPath: UriPath, isUserLogged: Boolean, userName: NonEmptyString = "mia".nonempty) =
    assertRule(uriRegex, uriPath, isMatched = true, isUserLogged, userName)

  private def assertNotMatchRule(uriRegex: RuntimeSingleResolvableVariable[Pattern], uriPath: UriPath, isUserLogged: Boolean, userName: NonEmptyString = "mia".nonempty) =
    assertRule(uriRegex, uriPath, isMatched = false, isUserLogged, userName)

  private def assertRule(uriRegex: RuntimeSingleResolvableVariable[Pattern], uriPath: UriPath, isMatched: Boolean, isUserLogged: Boolean, userName: NonEmptyString) = {
    val rule = new UriRegexRule(UriRegexRule.Settings(uriRegex))
    val blockContext = mock[BlockContext]
    val requestContext = uriRegex match {
      case AlreadyResolved(_) =>
        MockRequestContext(uriPath = uriPath)
      case ToBeResolved(_) if isUserLogged =>
        (blockContext.loggedUser _).expects().returning(Some(LoggedUser(Id(userName))))
        MockRequestContext(uriPath = uriPath)
      case ToBeResolved(_) =>
        (blockContext.loggedUser _).expects().returning(None)
        MockRequestContext.default
    }
    rule.check(requestContext, blockContext).runSyncStep shouldBe Right {
      if (isMatched) Fulfilled(blockContext)
      else Rejected
    }
  }

  private def patternValueFrom(value: String): RuntimeSingleResolvableVariable[Pattern] = {
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
