package tech.beshu.ror.acl.blocks.rules

import org.scalatest.Matchers._
import org.scalatest.WordSpec
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import tech.beshu.ror.acl.requestcontext.RequestContext

class ApiKeysRuleTests extends WordSpec with MockFactory {

  private val rule = new ApiKeysRule(ApiKeysRule.Settings(Set("1234567890")))

  "An ApiKeysRule" should {
    "match" in {
      val context = mock[RequestContext]
      (context.getHeaders _ ).expects().returning(Map("X-Api-Key" -> "1234567890"))
      rule.`match`(context).runSyncMaybe shouldBe Right(true)
    }

    "not match" in {
      val context = mock[RequestContext]
      (context.getHeaders _ ).expects().returning(Map("X-Api-Key" -> "x"))
      rule.`match`(context).runSyncMaybe shouldBe Right(false)
    }
  }
}
