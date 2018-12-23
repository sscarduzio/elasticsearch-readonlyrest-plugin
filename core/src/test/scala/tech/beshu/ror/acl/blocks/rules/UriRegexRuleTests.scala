package tech.beshu.ror.acl.blocks.rules

import java.util.regex.Pattern

import com.softwaremill.sttp.{Uri, UriContext}
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult
import tech.beshu.ror.acl.blocks.{BlockContext, Const, Value, Variable}
import tech.beshu.ror.commons.domain.LoggedUser
import tech.beshu.ror.commons.domain.User.Id
import tech.beshu.ror.mocks.MockRequestContext

class UriRegexRuleTests extends WordSpec with MockFactory {

  "An UriRegexRule" should {
    "match" when {
      "configured pattern matches uri from request" in {
        assertMatchRule(
          uriRegex = Value.fromString("""^http:\/\/one.com\/\d\d\d$""", rv => Pattern.compile(rv.value)),
          uri = uri"http://one.com/123",
          isUserLogged = false
        )
      }
      "configured pattern with variable matches uri from request when user is logged" in {
        assertMatchRule(
          uriRegex = Value.fromString("""^http:\/\/one.com\/@{user}$""", rv => Pattern.compile(rv.value)),
          uri = uri"http://one.com/mia",
          isUserLogged = true
        )
      }
    }
    "not matched" when {
      "configured pattern doesn't match uri from request" in {
        assertNotMatchRule(
          uriRegex = Value.fromString("""^http:\/\/one.com\/\d\d\d$""", rv => Pattern.compile(rv.value)),
          uri = uri"http://one.com/one",
          isUserLogged = false
        )
      }
      "configured pattern with variable doesn't matche uri from request when user is not logged" in {
        assertNotMatchRule(
          uriRegex = Value.fromString("""^http:\/\/one.com\/@{user}$""", rv => Pattern.compile(rv.value)),
          uri = uri"http://one.com/mia",
          isUserLogged = false
        )
      }
    }
  }

  private def assertMatchRule(uriRegex: Value[Pattern], uri: Uri, isUserLogged: Boolean) =
    assertRule(uriRegex, uri, isMatched = true, isUserLogged)

  private def assertNotMatchRule(uriRegex: Value[Pattern], uri: Uri, isUserLogged: Boolean) =
    assertRule(uriRegex, uri, isMatched = false, isUserLogged)

  private def assertRule(uriRegex: Value[Pattern], uri: Uri, isMatched: Boolean, isUserLogged: Boolean) = {
    val rule = new UriRegexRule(UriRegexRule.Settings(uriRegex))
    val blockContext = mock[BlockContext]
    val requestContext = uriRegex match {
      case Const(_) =>
        MockRequestContext(uri = uri)
      case Variable(_, _) if isUserLogged =>
        (blockContext.loggedUser _).expects().returning(Some(LoggedUser(Id("mia"))))
        MockRequestContext(uri = uri)
      case Variable(_, _) =>
        (blockContext.loggedUser _).expects().returning(None)
        MockRequestContext.default
    }
    rule.check(requestContext, blockContext).runSyncStep shouldBe Right(RuleResult.fromCondition(blockContext) {
      isMatched
    })
  }
}
