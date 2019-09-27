/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.accesscontrol.factory.decoders.rules

import cats.data.NonEmptySet
import cats.implicits._
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.accesscontrol.blocks.rules.FieldsRule
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.factory.decoders.rules.FieldsRuleDecoderHelper._
import tech.beshu.ror.accesscontrol.utils.CirceOps.{DecoderHelpers, _}
import tech.beshu.ror.Constants
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleWithVariableUsageDefinition
import tech.beshu.ror.accesscontrol.domain.DocumentField
import tech.beshu.ror.accesscontrol.domain.DocumentField.{ADocumentField, NegatedDocumentField}
import tech.beshu.ror.accesscontrol.orders._

import scala.collection.JavaConverters._
import scala.collection.SortedSet

object FieldsRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  DecoderHelpers
    .decodeStringLikeOrNonEmptySetE(toDocumentField)
    .toSyncDecoder
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
        val settings = NonEmptySet.fromSet(SortedSet.empty[ADocumentField] ++ nonNegatedFields.toSet) match {
          case Some(f) =>
            FieldsRule.Settings.ofFields(f)
          case None =>
            val negatedFieldsNes = NonEmptySet
              .fromSet(SortedSet.empty[NegatedDocumentField] ++ negatedFields.toSet)
                .getOrElse(throw new IllegalStateException("Should contain all negated fields"))
            FieldsRule.Settings.ofNegatedFields(negatedFieldsNes)
        }
        Right(settings)
      }
    }
    .map(settings => RuleWithVariableUsageDefinition.create(new FieldsRule(settings)))
    .decoder
)

private object FieldsRuleDecoderHelper {
  def toDocumentField(value: String): Either[String, DocumentField] = {
    if (value.startsWith("~")) {
      NonEmptyString.from(value.substring(1)) match {
        case Right(nes) => Right(NegatedDocumentField(nes))
        case Left(_) => Left("There was no name passed for blacklist field (~ only is forbidden)")
      }
    } else {
      NonEmptyString.from(value) match {
        case Right(nes) => Right(ADocumentField(nes))
        case Left(_) => Left("Field cannot be empty string")
      }
    }
  }

  def containsAlwaysAllowedFields(fields: NonEmptySet[DocumentField]): Boolean = {
    fields.toSortedSet.map(_.value).intersect(Constants.FIELDS_ALWAYS_ALLOW.asScala.map(NonEmptyString.unsafeFrom)).nonEmpty
  }
}
