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
package tech.beshu.ror.accesscontrol.factory.decoders.rules.auth.groups

import cats.implicits.*
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleName
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseGroupsRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariableCreator
import tech.beshu.ror.accesscontrol.domain.GroupsLogic
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleDecoder
import tech.beshu.ror.accesscontrol.factory.decoders.rules.auth.groups.GroupsLogicRepresentationDecoder.GroupsLogicDecodingResult

class GroupsRuleDecoder(usersDefinitions: Definitions[UserDef],
                        globalSettings: GlobalSettings,
                        implicit val variableCreator: RuntimeResolvableVariableCreator)
                       (implicit ev: RuleName[BaseGroupsRule[GroupsLogic]])
  extends RuleBaseDecoderWithoutAssociatedFields[BaseGroupsRule[GroupsLogic]] {

  override protected def decodingContext: RuleDecoder.DecodingContext = RuleDecoder.DecodingContext.RuleNameWithValue

  override protected def decoder: Decoder[RuleDefinition[BaseGroupsRule[GroupsLogic]]] = {
    new BaseGroupsRuleDecoder()(ev.name, usersDefinitions, globalSettings.userIdCaseSensitivity, variableCreator)
      .syncDecoder
      .emapE {
        case GroupsLogicDecodingResult.Success(Right(rule)) =>
          Right(RuleDefinition.create(rule))
        case GroupsLogicDecodingResult.Success(Left(error)) =>
          Left(error)
        case GroupsLogicDecodingResult.MultipleGroupsLogicsDefined(_, _) =>
          Left(RulesLevelCreationError(Message(s"No user definitions was defined. Rule `${ev.name.show}` requires them.")))
        case GroupsLogicDecodingResult.GroupsLogicNotDefined(_) =>
          Left(RulesLevelCreationError(Message(s"No user definitions was defined. Rule `${ev.name.show}` requires them.")))
      }
      .decoder
  }

}
