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

import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.acl.domain.User.Id
import tech.beshu.ror.acl.domain.{LoggedUser, UriPath}
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.rules.UriRegexRule
import tech.beshu.ror.acl.blocks.{BlockContext, Const, Value, Variable}
import tech.beshu.ror.mocks.MockRequestContext

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
          userName = "["
        )
      }
    }
  }

  private def assertMatchRule(uriRegex: Value[Pattern], uriPath: UriPath, isUserLogged: Boolean, userName: String = "mia") =
    assertRule(uriRegex, uriPath, isMatched = true, isUserLogged, userName)

  private def assertNotMatchRule(uriRegex: Value[Pattern], uriPath: UriPath, isUserLogged: Boolean, userName: String = "mia") =
    assertRule(uriRegex, uriPath, isMatched = false, isUserLogged, userName)

  private def assertRule(uriRegex: Value[Pattern], uriPath: UriPath, isMatched: Boolean, isUserLogged: Boolean, userName: String) = {
    val rule = new UriRegexRule(UriRegexRule.Settings(uriRegex))
    val blockContext = mock[BlockContext]
    val requestContext = uriRegex match {
      case Const(_) =>
        MockRequestContext(uriPath = uriPath)
      case Variable(_, _) if isUserLogged =>
        (blockContext.loggedUser _).expects().returning(Some(LoggedUser(Id(userName))))
        MockRequestContext(uriPath = uriPath)
      case Variable(_, _) =>
        (blockContext.loggedUser _).expects().returning(None)
        MockRequestContext.default
    }
    rule.check(requestContext, blockContext).runSyncStep shouldBe Right {
      if (isMatched) Fulfilled(blockContext)
      else Rejected
    }
  }

  private def patternValueFrom(value: String): Value[Pattern] = {
    Value
      .fromString(value, rv => Try(Pattern.compile(rv.value)).toEither.left.map(_ => Value.ConvertError(rv, "msg")))
      .right
      .getOrElse(throw new IllegalStateException(s"Cannot create Pattern Value from $value"))
  }
}
