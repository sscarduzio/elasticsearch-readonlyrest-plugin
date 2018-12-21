package tech.beshu.ror.acl.blocks.rules

import java.util.regex.Pattern

import com.softwaremill.sttp.Uri
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.commons.domain.{Const, Value, Variable}
import com.softwaremill.sttp.UriContext
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult

class UriRegexRuleTests extends WordSpec with MockFactory {

  "An UriRegexRule" should {
    "match" when {
      "configured pattern matches uri from request" in {
        assertMatchRule(
          uriRegex = Value.fromString("""^http:\/\/one.com\/\d\d\d$""", Pattern.compile),
          uri = uri"http://one.com/123",
          isUserLogged = false
        )
      }
      "configured pattern with variable matches uri from request when user is logged" in {
        assertMatchRule(
          uriRegex = Value.fromString("""^http:\/\/one.com\/@{user}$""", Pattern.compile),
          uri = uri"http://one.com/mia",
          isUserLogged = true
        )
      }
    }
    "not matched" when {
      "configured pattern doesn't match uri from request" in {
        assertNotMatchRule(
          uriRegex = Value.fromString("""^http:\/\/one.com\/\d\d\d$""", Pattern.compile),
          uri = uri"http://one.com/one",
          isUserLogged = false
        )
      }
      "configured pattern with variable doesn't matche uri from request when user is not logged" in {
        assertNotMatchRule(
          uriRegex = Value.fromString("""^http:\/\/one.com\/@{user}$""", Pattern.compile),
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
    val requestContext = mock[RequestContext]
    val blockContext = mock[BlockContext]
    uriRegex match {
      case Const(_) =>
        (requestContext.uri _).expects().returning(uri)
      case Variable(representation, _) if isUserLogged =>
        (requestContext.uri _).expects().returning(uri)
        (requestContext.resolve _).expects(representation).returning(Some(uri.toString()))
      case Variable(representation, _) =>
        (requestContext.resolve _).expects(representation).returning(None)
    }
    rule.check(requestContext, blockContext).runSyncStep shouldBe Right(RuleResult.fromCondition(blockContext) { isMatched })
  }
}
