package tech.beshu.ror.unit.acl.blocks.rules

import java.util.regex.Pattern

import com.softwaremill.sttp.{Uri, UriContext}
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.unit.acl.blocks.rules.Rule.RuleResult
import tech.beshu.ror.unit.acl.blocks.{BlockContext, Const, Value, Variable}
import tech.beshu.ror.commons.domain.LoggedUser
import tech.beshu.ror.commons.domain.User.Id
import tech.beshu.ror.mocks.MockRequestContext

import scala.util.Try

class UriRegexRuleTests extends WordSpec with MockFactory {

  "An UriRegexRule" should {
    "match" when {
      "configured pattern matches uri from request" in {
        assertMatchRule(
          uriRegex = patternValueFrom("""^http:\/\/one.com\/\d\d\d$"""),
          uri = uri"http://one.com/123",
          isUserLogged = false
        )
      }
      "configured pattern with variable matches uri from request when user is logged" in {
        assertMatchRule(
          uriRegex = patternValueFrom("""^http:\/\/one.com\/@{user}$"""),
          uri = uri"http://one.com/mia",
          isUserLogged = true
        )
      }
    }
    "not matched" when {
      "configured pattern doesn't match uri from request" in {
        assertNotMatchRule(
          uriRegex = patternValueFrom("""^http:\/\/one.com\/\d\d\d$"""),
          uri = uri"http://one.com/one",
          isUserLogged = false
        )
      }
      "configured pattern with variable doesn't match uri from request when user is not logged" in {
        assertNotMatchRule(
          uriRegex = patternValueFrom("""^http:\/\/one.com\/@{user}$"""),
          uri = uri"http://one.com/mia",
          isUserLogged = false
        )
      }
      "configured pattern with variable isn't able to compile to pattern after resolve" in {
        assertNotMatchRule(
          uriRegex = patternValueFrom("""^http:\/\/one.com\/@{user}$"""),
          uri = uri"http://one.com/mia",
          isUserLogged = true,
          userName = "["
        )
      }
    }
  }

  private def assertMatchRule(uriRegex: Value[Pattern], uri: Uri, isUserLogged: Boolean, userName: String = "mia") =
    assertRule(uriRegex, uri, isMatched = true, isUserLogged, userName)

  private def assertNotMatchRule(uriRegex: Value[Pattern], uri: Uri, isUserLogged: Boolean, userName: String = "mia") =
    assertRule(uriRegex, uri, isMatched = false, isUserLogged, userName)

  private def assertRule(uriRegex: Value[Pattern], uri: Uri, isMatched: Boolean, isUserLogged: Boolean, userName: String) = {
    val rule = new UriRegexRule(UriRegexRule.Settings(uriRegex))
    val blockContext = mock[BlockContext]
    val requestContext = uriRegex match {
      case Const(_) =>
        MockRequestContext(uri = uri)
      case Variable(_, _) if isUserLogged =>
        (blockContext.loggedUser _).expects().returning(Some(LoggedUser(Id(userName))))
        MockRequestContext(uri = uri)
      case Variable(_, _) =>
        (blockContext.loggedUser _).expects().returning(None)
        MockRequestContext.default
    }
    rule.check(requestContext, blockContext).runSyncStep shouldBe Right(RuleResult.fromCondition(blockContext) {
      isMatched
    })
  }

  private def patternValueFrom(value: String): Value[Pattern] = {
    Value
      .fromString(value, rv => Try(Pattern.compile(rv.value)).toEither.left.map(_ => Value.ConvertError(rv, "msg")))
      .right
      .getOrElse(throw new IllegalStateException(s"Cannot create Pattern Value from $value"))
  }
}
