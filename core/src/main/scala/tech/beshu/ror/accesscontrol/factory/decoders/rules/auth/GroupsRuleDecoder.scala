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
package tech.beshu.ror.accesscontrol.factory.decoders.rules.auth

import cats.data.NonEmptyList
import cats.implicits.*
import io.circe.{ACursor, Decoder, HCursor}
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleName
import tech.beshu.ror.accesscontrol.blocks.rules.auth.*
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeResolvableGroupsLogic, RuntimeResolvableVariableCreator}
import tech.beshu.ror.accesscontrol.domain.GroupsLogic.{NegativeGroupsLogic, PositiveGroupsLogic}
import tech.beshu.ror.accesscontrol.domain.{GroupIdLike, GroupsLogic}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.common.*
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.factory.decoders.rules.auth.GroupsLogicRepresentationDecoder.GroupsLogicDecodingResult
import tech.beshu.ror.accesscontrol.utils.CirceOps.*

class GroupsRuleDecoder(usersDefinitions: Definitions[UserDef],
                        globalSettings: GlobalSettings,
                        implicit val variableCreator: RuntimeResolvableVariableCreator)
                       (implicit ev: RuleName[GroupsRule[GroupsLogic]])
  extends RuleBaseDecoderWithoutAssociatedFields[GroupsRule[GroupsLogic]] {

  override protected def decodingContext(c: HCursor): ACursor = c

  override protected def decoder: Decoder[RuleDefinition[GroupsRule[GroupsLogic]]] = {
    runtimeResolvableGroupsLogicDecoder
      .syncDecoder
      .emapE {
        case GroupsLogicDecodingResult.Success(groupsLogic) =>
          NonEmptyList.fromList(usersDefinitions.items) match {
            case Some(userDefs) =>
              val settings = GroupsRule.Settings(groupsLogic, userDefs)
              val groupsRule = new GroupsRule[GroupsLogic](ev.name, globalSettings.userIdCaseSensitivity, settings)
              Right(RuleDefinition.create(groupsRule))
            case None =>
              Left(RulesLevelCreationError(Message(s"No user definitions was defined. Rule `${ev.name.show}` requires them.")))
          }
        case GroupsLogicDecodingResult.MultipleGroupsLogicsDefined(_, _) =>
          Left(RulesLevelCreationError(Message(s"No user definitions was defined. Rule `${ev.name.show}` requires them.")))
        case GroupsLogicDecodingResult.GroupsLogicNotDefined(_) =>
          Left(RulesLevelCreationError(Message(s"No user definitions was defined. Rule `${ev.name.show}` requires them.")))
      }
      .decoder
  }

  private def runtimeResolvableGroupsLogicDecoder = new GroupsLogicRepresentationDecoder[
    RuntimeResolvableGroupsLogic[GroupsLogic],
    RuntimeResolvableGroupsLogic.Simple[PositiveGroupsLogic],
    RuntimeResolvableGroupsLogic.Simple[NegativeGroupsLogic],
    RuntimeResolvableGroupsLogic.Simple[GroupsLogic.AllOf],
    RuntimeResolvableGroupsLogic.Simple[GroupsLogic.AnyOf],
    RuntimeResolvableGroupsLogic.Simple[GroupsLogic.NotAllOf],
    RuntimeResolvableGroupsLogic.Simple[GroupsLogic.NotAnyOf],
  ]((positive, negative) => RuntimeResolvableGroupsLogic.Combined(positive, negative))

  private implicit def runtimeResolvableGroupsLogic[GL <: GroupsLogic : GroupsLogic.Creator]: Decoder[RuntimeResolvableGroupsLogic.Simple[GL]] = {
    DecoderHelpers
      .decoderStringLikeOrUniqueNonEmptyList[RuntimeMultiResolvableVariable[GroupIdLike]]
      .map(RuntimeResolvableGroupsLogic.Simple[GL](_))
  }

}
