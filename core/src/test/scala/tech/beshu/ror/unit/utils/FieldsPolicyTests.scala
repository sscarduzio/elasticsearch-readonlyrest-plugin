package tech.beshu.ror.unit.utils

import cats.data.NonEmptySet
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.accesscontrol.domain.DocumentField.{ADocumentField, NegatedDocumentField}
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.fls.FieldsPolicy
import tech.beshu.ror.utils.TestsUtils._

class FieldsPolicyTests extends WordSpec {

  "A FieldMatcher" should {
    "work in whitelist mode" in {
      val matcher = new FieldsPolicy(NonEmptySet.of(
        ADocumentField("it*re*bus1".nonempty), ADocumentField("item.*Date".nonempty)
      ))

      matcher.canKeep("itemresobus2resobus1") should be (true)
      matcher.canKeep("item.endDate") should be (true)
      matcher.canKeep("item") should be (true)
      matcher.canKeep("item.endDate.text") should be (false)
      matcher.canKeep("item.endDate1") should be (false)
    }
    "work in blacklist mode" in {
      val matcher = new FieldsPolicy(NonEmptySet.of(
        NegatedDocumentField("it*re*bus1".nonempty), NegatedDocumentField("item.*Date".nonempty)
      ))

      matcher.canKeep("itemresobus2resobus1") should be (false)
      matcher.canKeep("item.endDate") should be (false)
      matcher.canKeep("item") should be (true)
      matcher.canKeep("item.endDate.text") should be (false)
      matcher.canKeep("item.endDate1") should be (true)
    }
  }
}
