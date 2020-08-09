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

import cats.Order
import cats.data.NonEmptySet
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Decoder
import tech.beshu.ror.Constants
import tech.beshu.ror.accesscontrol.blocks.rules.FieldsRule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleWithVariableUsageDefinition
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible
import tech.beshu.ror.accesscontrol.domain.DocumentField
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.common._
import tech.beshu.ror.accesscontrol.factory.decoders.rules.FieldsRuleDecoderHelper.documentFieldDecoder
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.utils.CirceOps.{DecoderHelpers, _}

import scala.collection.JavaConverters._

object FieldsRuleDecoder extends RuleDecoderWithoutAssociatedFields(documentFieldDecoder)

private object FieldsRuleDecoderHelper {

  private type SettingsCreator[FIELD_TYPE] = NonEmptySet[RuntimeMultiResolvableVariable[FIELD_TYPE]] => FieldsRule.Settings

  private val aDocumentFieldDecoder: Decoder[RuleWithVariableUsageDefinition[FieldsRule]] = documentFieldDecoder(FieldsRule.Settings.ofFields)
  private val negatedDocumentFieldDecoder: Decoder[RuleWithVariableUsageDefinition[FieldsRule]] = documentFieldDecoder(FieldsRule.Settings.ofNegatedFields)

  val documentFieldDecoder: Decoder[RuleWithVariableUsageDefinition[FieldsRule]] =
    aDocumentFieldDecoder.or(negatedDocumentFieldDecoder)
      .toSyncDecoder
      .emapE { ruleWithContext =>
        if (containsAlwaysAllowedFields(ruleWithContext.rule.settings.fields)) {
          Left(RulesLevelCreationError(Message(s"The fields rule cannot contain always-allowed fields: ${Constants.FIELDS_ALWAYS_ALLOW.asScala.mkString(",")}")))
        } else {
          Right(ruleWithContext)
        }
      }
      .decoder

  private def documentFieldDecoder[FIELD_TYPE <: DocumentField : Order : Convertible](settingsCreator: SettingsCreator[FIELD_TYPE]) = {
    DecoderHelpers
      .decodeStringLikeOrNonEmptySet[RuntimeMultiResolvableVariable[FIELD_TYPE]]
      .map(settingsCreator)
      .map(settings => RuleWithVariableUsageDefinition.create(new FieldsRule(settings)))
  }

  private def containsAlwaysAllowedFields[FIELD_TYPE <: DocumentField](fields: NonEmptySet[RuntimeMultiResolvableVariable[FIELD_TYPE]]): Boolean = {
    fields.toNonEmptyList
      .collect {
        case resolved: RuntimeMultiResolvableVariable.AlreadyResolved[FIELD_TYPE] => resolved.value.toList
      }
      .flatten
      .map(_.value)
      .intersect(Constants.FIELDS_ALWAYS_ALLOW.asScala.map(NonEmptyString.unsafeFrom).toList)
      .nonEmpty
  }
}
