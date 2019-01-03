package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.commons.aDomain.DocumentField.ADocumentField
import tech.beshu.ror.commons.aDomain.Header
import tech.beshu.ror.commons.orders._

class FieldsRuleTests extends WordSpec with MockFactory {

  "A FieldsRule" should {
    "match" when {
      "request is read only" in {
        val rule = new FieldsRule(FieldsRule.Settings.ofFields(NonEmptySet.of(ADocumentField("_field1"), ADocumentField("_field2")), None))
        val requestContext = mock[RequestContext]
        val blockContext = mock[BlockContext]
        val newBlockContext = mock[BlockContext]
        (requestContext.isReadOnlyRequest _).expects().returning(true)
        (blockContext.addContextHeader _).expects(Header("_fields" -> "_field1,_field2")).returning(newBlockContext)
        rule.check(requestContext, blockContext).runSyncStep shouldBe Right(Fulfilled(newBlockContext))
      }
    }
    "not match" when {
      "request is not read only" in {
        val rule = new FieldsRule(FieldsRule.Settings.ofFields(NonEmptySet.of(ADocumentField("_field1")), None))
        val requestContext = mock[RequestContext]
        val blockContext = mock[BlockContext]
        (requestContext.isReadOnlyRequest _).expects().returning(false)
        rule.check(requestContext, blockContext).runSyncStep shouldBe Right(Rejected)
      }
    }
  }

}
