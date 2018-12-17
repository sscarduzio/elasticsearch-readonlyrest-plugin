package tech.beshu.ror.acl.blocks.rules

import org.scalatest.Matchers._
import org.scalamock.scalatest.MockFactory
import org.scalatest.WordSpec
import tech.beshu.ror.acl.request.RequestContext
import monix.execution.Scheduler.Implicits.global
import tech.beshu.ror.TestsUtils.basicAuthHeader
import tech.beshu.ror.commons.domain.LoggedUser
import tech.beshu.ror.commons.domain.User.Id

trait BasicAuthenticationTestTemplate extends WordSpec with MockFactory {

  protected def ruleName: String
  protected def rule: BasicAuthenticationRule

  s"An $ruleName" should {
    "match" when {
      "basic auth header contains configured in rule's settings value" in {
        val context = mock[RequestContext]
        (context.headers _).expects().returning(Set(basicAuthHeader("logstash:logstash")))
        (context.setLoggedInUser _).expects(LoggedUser(Id("logstash")))
        rule.`match`(context).runSyncStep shouldBe Right(true)
      }
    }

    "not match" when {
      "basic auth header contains not configured in rule's settings value" in {
        val context = mock[RequestContext]
        (context.headers _).expects().returning(Set(basicAuthHeader("logstash:nologstash")))
        rule.`match`(context).runSyncStep shouldBe Right(false)
      }
      "basic auth header is absent" in {
        val context = mock[RequestContext]
        (context.headers _).expects().returning(Set.empty)
        rule.`match`(context).runSyncStep shouldBe Right(false)
      }
    }
  }

}