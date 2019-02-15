package tech.beshu.ror.acl.factory.decoders.rules

import cats.data.NonEmptySet
import cats.implicits._
import tech.beshu.ror.acl.blocks.rules.FieldsRule
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.factory.decoders.rules.FieldsRuleDecoderHelper._
import tech.beshu.ror.acl.utils.CirceOps.{DecoderHelpers, _}
import tech.beshu.ror.Constants
import tech.beshu.ror.acl.aDomain.DocumentField
import tech.beshu.ror.acl.aDomain.DocumentField.{ADocumentField, NegatedDocumentField}
import tech.beshu.ror.acl.orders._

import scala.collection.JavaConverters._
import scala.collection.SortedSet

object FieldsRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  DecoderHelpers
    .decodeStringLikeOrNonEmptySet(toDocumentField)
    .emapE { fields =>
      val (negatedFields, nonNegatedFields) = fields.toList.partitionEither {
        case d: ADocumentField => Right(d)
        case d: NegatedDocumentField => Left(d)
      }
      if (negatedFields.nonEmpty && nonNegatedFields.nonEmpty) {
        Left(RulesLevelCreationError(Message(s"fields should all be negated (i.e. '~field1') or all without negation (i.e. 'field1') Found: ${fields.map(_.value).toSortedSet.mkString(",")}")))
      } else if (containsAlwaysAllowedFields(fields)) {
        Left(RulesLevelCreationError(Message(s"The fields rule cannot contain always-allowed fields: ${Constants.FIELDS_ALWAYS_ALLOW.asScala.mkString(",")}")))
      } else {
        val notAllField = if (fields.exists(_ == DocumentField.all)) None else Some(DocumentField.notAll)
        val settings = NonEmptySet.fromSet(SortedSet.empty[ADocumentField] ++ nonNegatedFields.toSet) match {
          case Some(f) =>
            FieldsRule.Settings.ofFields(f, notAllField)
          case None =>
            val negatedFieldsNes = NonEmptySet
              .fromSet(SortedSet.empty[NegatedDocumentField] ++ negatedFields.toSet)
                .getOrElse(throw new IllegalStateException("Should contain all negated fields"))
            FieldsRule.Settings.ofNegatedFields(negatedFieldsNes, notAllField)
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
