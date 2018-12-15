package tech.beshu.ror.acl.blocks.rules

import java.util.regex.Pattern

import io.lemonlabs.uri.Uri
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.acl.requestcontext.RequestContext
import tech.beshu.ror.commons.domain.{Const, Value, Variable}

class UriRegexRuleTests extends WordSpec with MockFactory {

  "A UriRegexRule" should {
    "match" when {
      "configured pattern matches uri from request" in {
        assertMatchRule(
          uriRegex = Value.fromString("""^http:\/\/one.com\/\d\d\d$""", Pattern.compile),
          uri = Uri.parse("http://one.com/123"),
          isUserLogged = false
        )
      }
      "configured pattern with variable matches uri from request when user is logged" in {
        assertMatchRule(
          uriRegex = Value.fromString("""^http:\/\/one.com\/@{user}$""", Pattern.compile),
          uri = Uri.parse("http://one.com/mia"),
          isUserLogged = true
        )
      }
    }
    "not matched" when {
      "configured pattern doesn't match uri from request" in {
        assertNotMatchRule(
          uriRegex = Value.fromString("""^http:\/\/one.com\/\d\d\d$""", Pattern.compile),
          uri = Uri.parse("http://one.com/one"),
          isUserLogged = false
        )
      }
      "configured pattern with variable doesn't matche uri from request when user is not logged" in {
        assertNotMatchRule(
          uriRegex = Value.fromString("""^http:\/\/one.com\/@{user}$""", Pattern.compile),
          uri = Uri.parse("http://one.com/mia"),
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
    val context = mock[RequestContext]
    uriRegex match {
      case Const(_) =>
        (context.uri _).expects().returning(uri)
      case Variable(representation, _) if isUserLogged =>
        (context.uri _).expects().returning(uri)
        (context.resolve _).expects(representation).returning(Some(uri.toString()))
      case Variable(representation, _) =>
        (context.resolve _).expects(representation).returning(None)
    }
    rule.`match`(context).runSyncStep shouldBe Right(isMatched)
  }
}
