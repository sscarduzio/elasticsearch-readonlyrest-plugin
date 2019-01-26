package tech.beshu.ror.unit.acl.blocks.rules

import cats.data.NonEmptySet
import eu.timepit.refined.types.string.NonEmptyString
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.TestsUtils._
import tech.beshu.ror.acl.aDomain.ApiKey
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.ApiKeysRule
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.acl.request.RequestContext

class ApiKeysRuleTests extends WordSpec with MockFactory {

  private val rule = new ApiKeysRule(ApiKeysRule.Settings(NonEmptySet.of(ApiKey(NonEmptyString.unsafeFrom("1234567890")))))

  "An ApiKeysRule" should {
    "match" when {
      "x-api-key header contains defined in settings value" in {
        val requestContext = mock[RequestContext]
        val blockContext = mock[BlockContext]
        (requestContext.headers _).expects().returning(Set(headerFrom("X-Api-Key" -> "1234567890")))
        rule.check(requestContext, blockContext).runSyncStep shouldBe Right(Fulfilled(blockContext))
      }
    }

    "not match" when {
      "x-api-key header contains not defined in settings value" in {
        val requestContext = mock[RequestContext]
        val blockContext = mock[BlockContext]
        (requestContext.headers _).expects().returning(Set(headerFrom("X-Api-Key" -> "x")))
        rule.check(requestContext, blockContext).runSyncStep shouldBe Right(Rejected)
      }
      "x-api-key header is absent" in {
        val requestContext = mock[RequestContext]
        val blockContext = mock[BlockContext]
        (requestContext.headers _).expects().returning(Set.empty)
        rule.check(requestContext, blockContext).runSyncStep shouldBe Right(Rejected)
      }
    }
  }
}
