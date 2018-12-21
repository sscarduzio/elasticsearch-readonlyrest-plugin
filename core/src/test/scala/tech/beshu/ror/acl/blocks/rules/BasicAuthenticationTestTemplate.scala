package tech.beshu.ror.acl.blocks.rules

import org.scalatest.Matchers._
import org.scalamock.scalatest.MockFactory
import org.scalatest.WordSpec
import tech.beshu.ror.acl.request.RequestContext
import monix.execution.Scheduler.Implicits.global
import tech.beshu.ror.TestsUtils.basicAuthHeader
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult
import tech.beshu.ror.commons.domain.LoggedUser
import tech.beshu.ror.commons.domain.User.Id

trait BasicAuthenticationTestTemplate extends WordSpec with MockFactory {

  protected def ruleName: String
  protected def rule: BasicAuthenticationRule

  s"An $ruleName" should {
    "match" when {
      "basic auth header contains configured in rule's settings value" in {
        val requestContext = mock[RequestContext]
        val blockContext = mock[BlockContext]
        val modifiedBlockContext = mock[BlockContext]
        (requestContext.headers _).expects().returning(Set(basicAuthHeader("logstash:logstash")))
        (blockContext.setLoggedUser _).expects(LoggedUser(Id("logstash"))).returning(modifiedBlockContext)
        rule.check(requestContext, blockContext).runSyncStep shouldBe Right(RuleResult.Fulfilled(modifiedBlockContext))
      }
    }

    "not match" when {
      "basic auth header contains not configured in rule's settings value" in {
        val requestContext = mock[RequestContext]
        val blockContext = mock[BlockContext]
        (requestContext.headers _).expects().returning(Set(basicAuthHeader("logstash:nologstash")))
        rule.check(requestContext, blockContext).runSyncStep shouldBe Right(RuleResult.Rejected)
      }
      "basic auth header is absent" in {
        val requestContext = mock[RequestContext]
        val blockContext = mock[BlockContext]
        (requestContext.headers _).expects().returning(Set.empty)
        rule.check(requestContext, blockContext).runSyncStep shouldBe Right(RuleResult.Rejected)
      }
    }
  }

}