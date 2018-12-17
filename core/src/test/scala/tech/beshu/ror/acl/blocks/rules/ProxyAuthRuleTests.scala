package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.commons.aDomain.Header
import tech.beshu.ror.commons.domain.User.Id
import tech.beshu.ror.commons.domain.{LoggedUser, User}
import tech.beshu.ror.commons.orders._

class ProxyAuthRuleTests extends WordSpec with MockFactory {

  "A ProxyAuthRule" should {
    "match" when {
      "one user id is configured and the same id can be find in auth header" in {
        assertMatchRule(
          settings = ProxyAuthRule.Settings(NonEmptySet.of(User.Id("userA")), Header.Name("custom-user-auth-header")),
          header = Header("custom-user-auth-header" -> "userA")
        )
      }
      "several user ids are configured and one of them can be find in auth header" in {
        assertMatchRule(
          settings = ProxyAuthRule.Settings(
            NonEmptySet.of(User.Id("userA"), User.Id("userB"), User.Id("userC")),
            Header.Name("custom-user-auth-header")
          ),
          header = Header("custom-user-auth-header" -> "userB")
        )
      }
    }
    "not match" when {
      "none of configured user ids corresponds to the auth header one" in {
        assertNotMatchRule(
          settings = ProxyAuthRule.Settings(
            NonEmptySet.of(User.Id("userA"), User.Id("userB"), User.Id("userC")),
            Header.Name("custom-user-auth-header")
          ),
          header = Header("custom-user-auth-header" -> "userD")
        )
      }
      "user id is passed in different header than the configured one" in {
        assertNotMatchRule(
          settings = ProxyAuthRule.Settings(
            NonEmptySet.of(User.Id("userA")),
            Header.Name("custom-user-auth-header")
          ),
          header = Header("X-Forwarded-User" -> "userD")
        )
      }
    }
  }

  private def assertMatchRule(settings: ProxyAuthRule.Settings, header: Header) =
    assertRule(settings, header, isMatched = true)

  private def assertNotMatchRule(settings: ProxyAuthRule.Settings, header: Header) =
    assertRule(settings, header, isMatched = false)

  private def assertRule(settings: ProxyAuthRule.Settings, header: Header, isMatched: Boolean) = {
    val rule = new ProxyAuthRule(settings)
    val context = mock[RequestContext]
    (context.headers _).expects().returning(Set(header))
    if(isMatched) (context.setLoggedInUser _).expects(LoggedUser(Id(header.value)))
    rule.`match`(context).runSyncStep shouldBe Right(isMatched)
  }
}
