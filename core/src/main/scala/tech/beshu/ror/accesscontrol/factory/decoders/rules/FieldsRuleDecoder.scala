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
import tech.beshu.ror.Constants
import tech.beshu.ror.accesscontrol.blocks.rules.FieldsRule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleWithVariableUsageDefinition
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.domain.DocumentField
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.common._
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.utils.CirceOps.{DecoderHelpers, _}

import scala.collection.JavaConverters._

object FieldsRuleDecoder extends RuleDecoderWithoutAssociatedFields(FieldsRuleDecoderHelper.fieldsRuleDecoder)

private object FieldsRuleDecoderHelper {

  val fieldsRuleDecoder = DecoderHelpers
    .decodeStringLikeOrNonEmptySet[RuntimeMultiResolvableVariable[DocumentField]]
    .toSyncDecoder
    .emapE(validateDecodedFields)
    .map(settings => RuleWithVariableUsageDefinition.create(new FieldsRule(settings)))
    .decoder

  private def validateDecodedFields(fields: NonEmptySet[RuntimeMultiResolvableVariable[DocumentField]]) = {
    val resolvedFields = extractAlreadyResolvedFields(fields)

    if (DocumentField.areDifferentAccessModesUsedSimultaneously(resolvedFields)) {
      Left(RulesLevelCreationError(Message(s"fields should all be negated (i.e. '~field1') or all without negation (i.e. 'field1') Found: ${resolvedFields.map(_.value).toSet.mkString(",")}")))
    } else if (containsAlwaysAllowedFields(resolvedFields)) {
      Left(RulesLevelCreationError(Message(s"The fields rule cannot contain always-allowed fields: ${Constants.FIELDS_ALWAYS_ALLOW.asScala.mkString(",")}")))
    } else {
      Right(FieldsRule.Settings(fields))
    }
  }

  private def extractAlreadyResolvedFields(fields: NonEmptySet[RuntimeMultiResolvableVariable[DocumentField]]) = {
    fields.toList
      .collect {
        case alreadyResolved: RuntimeMultiResolvableVariable.AlreadyResolved[DocumentField] => alreadyResolved.value.toList
      }
      .flatten
  }

  private def containsAlwaysAllowedFields(fields: List[DocumentField]): Boolean = {
    fields
      .map(_.value)
      .intersect(Constants.FIELDS_ALWAYS_ALLOW.asScala.map(NonEmptyString.unsafeFrom).toList)
      .nonEmpty
  }
}
