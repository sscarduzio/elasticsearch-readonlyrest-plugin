package tech.beshu.ror.acl.factory.decoders

import cats.data.NonEmptySet
import cats.implicits._
import tech.beshu.ror.acl.blocks.rules.FieldsRule
import tech.beshu.ror.acl.factory.decoders.FieldsRuleDecoderHelper._
import tech.beshu.ror.acl.factory.decoders.ruleDecoders.RuleDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.utils.CirceOps.DecoderOps
import tech.beshu.ror.commons.Constants
import tech.beshu.ror.commons.aDomain.DocumentField
import tech.beshu.ror.commons.aDomain.DocumentField.{ADocumentField, NegatedDocumentField}
import tech.beshu.ror.commons.orders._

import scala.collection.JavaConverters._

object FieldsRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  DecoderOps
    .decodeStringLikeOrNonEmptySet(toDocumentField)
    .emap { fields =>
      val notNegatedFields = fields.collect { case f: ADocumentField => f }
      if (notNegatedFields.size != fields.size) {
        Left(s"fields should all be negated (i.e. '~field1') or all without negation (i.e. 'field1') Found: ${fields.map(_.value).toSortedSet.mkString(",")}")
      } else if (containsAlwaysAllowedFields(fields)) {
        Left(s"The fields rule cannot contain always-allowed fields: ${Constants.FIELDS_ALWAYS_ALLOW.asScala.mkString(",")}")
      } else {
        val notAllField = if (fields.exists(_ == DocumentField.all)) None else Some(DocumentField.notAll)
        val settings = notNegatedFields.toNes match {
          case Some(f) =>
            FieldsRule.Settings.ofFields(f, notAllField)
          case None =>
            val negatedFields = fields
              .collect { case f: NegatedDocumentField => f }
              .toNes
              .getOrElse(throw new IllegalStateException("Should contain all negated fileds"))
            FieldsRule.Settings.ofNegatedFields(negatedFields, notAllField)
        }
        Right(settings)
      }
    }
    .map(new FieldsRule(_))
)

private object FieldsRuleDecoderHelper {
  def toDocumentField(value: String): DocumentField = {
    if (value.startsWith("~")) NegatedDocumentField(value.substring(1))
    else ADocumentField(value)
  }

  def containsAlwaysAllowedFields(fields: NonEmptySet[DocumentField]): Boolean = {
    fields.toSortedSet.map(_.value).intersect(Constants.FIELDS_ALWAYS_ALLOW.asScala).nonEmpty
  }
}
