package tech.beshu.ror.acl.blocks.rules

import org.scalatest.Matchers._
import org.scalatest.WordSpec
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import tech.beshu.ror.acl.requestcontext.RequestContext
import tech.beshu.ror.commons.aDomain.Header

class ApiKeysRuleTests extends WordSpec with MockFactory {

  private val rule = new ApiKeysRule(ApiKeysRule.Settings(Set("1234567890")))

  "An ApiKeysRule" should {
    "match" when {
      "x-api-key header contains defined in settings value" in {
        val context = mock[RequestContext]
        (context.getHeaders _).expects().returning(Set(Header.create("X-Api-Key" -> "1234567890")))
        rule.`match`(context).runSyncStep shouldBe Right(true)
      }
    }

    "not match" when {
      "x-api-key header contains not defined in settings value" in {
        val context = mock[RequestContext]
        (context.getHeaders _).expects().returning(Set(Header.create("X-Api-Key" -> "x")))
        rule.`match`(context).runSyncStep shouldBe Right(false)
      }
      "x-api-key header is absent" in {
        val context = mock[RequestContext]
        (context.getHeaders _).expects().returning(Set.empty)
        rule.`match`(context).runSyncStep shouldBe Right(false)
      }
    }
  }
}
