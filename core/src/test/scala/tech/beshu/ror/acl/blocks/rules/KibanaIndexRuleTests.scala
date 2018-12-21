package tech.beshu.ror.acl.blocks.rules

import org.scalamock.scalatest.MockFactory
import org.scalatest.WordSpec
import org.scalatest.Matchers._
import monix.execution.Scheduler.Implicits.global
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.Fulfilled
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.commons.aDomain.IndexName
import tech.beshu.ror.commons.domain.Value

class KibanaIndexRuleTests extends WordSpec with MockFactory {

  "A KibanaIndexRule" should {
    "always match" should {
      "set kibana index if can be resolved" in {
        val rule = new KibanaIndexRule(KibanaIndexRule.Settings(Value.fromString("kibana_index", IndexName.apply)))
        val requestContext = mock[RequestContext]
        val blockContext = mock[BlockContext]
        val newBlockContext = mock[BlockContext]
        (blockContext.setKibanaIndex _).expects(IndexName("kibana_index")).returning(newBlockContext)
        rule.check(requestContext, blockContext).runSyncStep shouldBe Right(Fulfilled(newBlockContext))
      }
      "not set kibana index if cannot be resolved" in {
        val rule = new KibanaIndexRule(KibanaIndexRule.Settings(Value.fromString("kibana_index_of_@{user}", IndexName.apply)))
        val requestContext = mock[RequestContext]
        val blockContext = mock[BlockContext]
        (requestContext.resolve _).expects("kibana_index_of_@{user}").returning(None)
        rule.check(requestContext, blockContext).runSyncStep shouldBe Right(Fulfilled(blockContext))
      }
    }
  }
}
