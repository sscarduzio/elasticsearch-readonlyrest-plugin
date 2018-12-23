package tech.beshu.ror.acl.blocks.rules

import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.Fulfilled
import tech.beshu.ror.acl.blocks.{BlockContext, Value}
import tech.beshu.ror.commons.aDomain.IndexName
import tech.beshu.ror.mocks.MockRequestContext

class KibanaIndexRuleTests extends WordSpec with MockFactory {

  "A KibanaIndexRule" should {
    "always match" should {
      "set kibana index if can be resolved" in {
        val rule = new KibanaIndexRule(KibanaIndexRule.Settings(Value.fromString("kibana_index", rv => IndexName(rv.value))))
        val requestContext = MockRequestContext.default
        val blockContext = mock[BlockContext]
        val newBlockContext = mock[BlockContext]
        (blockContext.setKibanaIndex _).expects(IndexName("kibana_index")).returning(newBlockContext)
        rule.check(requestContext, blockContext).runSyncStep shouldBe Right(Fulfilled(newBlockContext))
      }
      "not set kibana index if cannot be resolved" in {
        val rule = new KibanaIndexRule(KibanaIndexRule.Settings(Value.fromString("kibana_index_of_@{user}", rv => IndexName(rv.value))))
        val requestContext = MockRequestContext.default
        val blockContext = mock[BlockContext]
        (blockContext.loggedUser _).expects().returning(None)
        rule.check(requestContext, blockContext).runSyncStep shouldBe Right(Fulfilled(blockContext))
      }
    }
  }
}
