package tech.beshu.ror.acl.blocks.rules

import org.scalamock.scalatest.MockFactory
import org.scalatest.WordSpec
import org.scalatest.Matchers._
import monix.execution.Scheduler.Implicits.global
import tech.beshu.ror.acl.requestcontext.RequestContext
import tech.beshu.ror.commons.aDomain.IndexName
import tech.beshu.ror.commons.domain.Value

class KibanaIndexRuleTests extends WordSpec with MockFactory {

  "A KibanaIndexRule" should {
    "always match" should {
      "set kibana index if can be resolved" in {
        val rule = new KibanaIndexRule(KibanaIndexRule.Settings(Value.fromString("kibana_index", IndexName.apply)))
        val context = mock[RequestContext]
        (context.setKibanaIndex _).expects(IndexName("kibana_index"))
        rule.`match`(context).runSyncStep shouldBe Right(true)
      }
      "not set kibana index if cannot be resolved" in {
        val rule = new KibanaIndexRule(KibanaIndexRule.Settings(Value.fromString("kibana_index_of_@{user}", IndexName.apply)))
        val context = mock[RequestContext]
        (context.resolve _).expects("kibana_index_of_@{user}").returning(None)
        rule.`match`(context).runSyncStep shouldBe Right(true)
      }
    }
  }
}
