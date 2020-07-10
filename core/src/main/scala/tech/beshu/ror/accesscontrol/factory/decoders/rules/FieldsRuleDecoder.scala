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
import tech.beshu.ror.accesscontrol.domain.DocumentField.{ADocumentField, NegatedDocumentField}
import tech.beshu.ror.accesscontrol.factory.decoders.common._
import tech.beshu.ror.accesscontrol.factory.decoders.rules.FieldsRuleDecoderHelper.{aDocumentFieldDecoder, negatedDocumentFieldDecoder}
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.utils.CirceOps.{DecoderHelpers, _}

import scala.collection.JavaConverters._

object FieldsRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  aDocumentFieldDecoder.or(negatedDocumentFieldDecoder)
)

private object FieldsRuleDecoderHelper {

  val aDocumentFieldDecoder = DecoderHelpers
    .decodeStringLikeOrNonEmptySet[RuntimeMultiResolvableVariable[ADocumentField]]
    .toSyncDecoder
    .map { fields => FieldsRule.Settings.ofFields(fields) }
    .map(settings => RuleWithVariableUsageDefinition.create(new FieldsRule(settings)))
    .decoder

  val negatedDocumentFieldDecoder = DecoderHelpers
    .decodeStringLikeOrNonEmptySet[RuntimeMultiResolvableVariable[NegatedDocumentField]]
    .toSyncDecoder
    .map { fields => FieldsRule.Settings.ofNegatedFields(fields) }
    .map(settings => RuleWithVariableUsageDefinition.create(new FieldsRule(settings)))
    .decoder

  def containsAlwaysAllowedFields(fields: NonEmptySet[DocumentField]): Boolean = {
    fields.toSortedSet.map(_.value).intersect(Constants.FIELDS_ALWAYS_ALLOW.asScala.map(NonEmptyString.unsafeFrom)).nonEmpty
  }
}
