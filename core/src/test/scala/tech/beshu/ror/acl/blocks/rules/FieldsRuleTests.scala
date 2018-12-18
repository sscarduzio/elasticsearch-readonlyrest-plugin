package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.commons.aDomain.DocumentField.ADocumentField
import tech.beshu.ror.commons.aDomain.Header
import tech.beshu.ror.commons.orders._

class FieldsRuleTests extends WordSpec with MockFactory {

  "A FieldsRule" should {
    "match" when {
      "request is read only" in {
        val rule = new FieldsRule(FieldsRule.Settings.ofFields(NonEmptySet.of(ADocumentField("_field1"), ADocumentField("_field2"))))
        val context = mock[RequestContext]
        (context.isReadOnlyRequest _).expects().returning(true)
        (context.setContextHeader _).expects(Header("_fields" -> "_field1,_field2"))
        rule.`match`(context).runSyncStep shouldBe Right(true)
      }
    }
    "not match" when {
      "request is not read only" in {
        val rule = new FieldsRule(FieldsRule.Settings.ofFields(NonEmptySet.of(ADocumentField("_field1"))))
        val context = mock[RequestContext]
        (context.isReadOnlyRequest _).expects().returning(false)
        rule.`match`(context).runSyncStep shouldBe Right(false)
      }
    }
  }

}
