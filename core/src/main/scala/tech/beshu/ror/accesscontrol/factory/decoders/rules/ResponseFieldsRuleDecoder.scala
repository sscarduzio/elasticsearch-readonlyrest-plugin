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

import tech.beshu.ror.accesscontrol.blocks.rules.ResponseFieldsRule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleWithVariableUsageDefinition
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.AlwaysRightConvertible
import tech.beshu.ror.accesscontrol.domain.ResponseFieldsFiltering.{AccessMode, ResponseField}
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields

object ResponseFieldsRuleDecoder extends RuleDecoderWithoutAssociatedFields(ResponseFieldsRuleDecoderHelper.fieldsRuleDecoder)

private object ResponseFieldsRuleDecoderHelper extends FieldsRuleLikeDecoderHelperBase {

  private implicit val convertible: Convertible[ResponseField] = AlwaysRightConvertible.from(ResponseField.apply)

  implicit val accessModeConverter: AccessModeConverter[AccessMode] =
    AccessModeConverter.create(whitelistElement = AccessMode.Whitelist, blacklistElement = AccessMode.Blacklist)

  val fieldsRuleDecoder = for {
    configuredFields <- configuredFieldsDecoder
    accessMode <- accessModeDecoder[AccessMode](configuredFields)
    documentFields <- documentFieldsDecoder[ResponseField](configuredFields, Set.empty)
  } yield RuleWithVariableUsageDefinition.create(new ResponseFieldsRule(ResponseFieldsRule.Settings(documentFields, accessMode)))
}
