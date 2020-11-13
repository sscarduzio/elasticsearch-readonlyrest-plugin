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

import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.rules.FieldsRule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleWithVariableUsageDefinition
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.AlwaysRightConvertible
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions.{AccessMode, DocumentField}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings.FlsEngine
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields

class FieldsRuleDecoder(flsEngine: FlsEngine)
  extends RuleDecoderWithoutAssociatedFields[FieldsRule](FieldsRuleDecoderHelper.fieldsRuleDecoder(flsEngine))

private object FieldsRuleDecoderHelper extends FieldsFilteringRuleBase {

  private implicit val convertible: Convertible[DocumentField] = AlwaysRightConvertible.from(DocumentField.apply)

  implicit val accessModeConverter: AccessModeConverter[AccessMode] =
    AccessModeConverter.create(whitelistElement = AccessMode.Whitelist, blacklistElement = AccessMode.Blacklist)

  def fieldsRuleDecoder(flsEngine: FlsEngine): Decoder[RuleWithVariableUsageDefinition[FieldsRule]] = {
    for {
      configuredFields <- configuredFieldsDecoder
      accessMode <- accessModeDecoder[AccessMode](configuredFields)
      documentFields <- documentFieldsDecoder[DocumentField](configuredFields, checkForAlwaysAllowedFields = true)
    } yield RuleWithVariableUsageDefinition.create(new FieldsRule(FieldsRule.Settings(documentFields, accessMode, flsEngine)))
  }


}
