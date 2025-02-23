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
        case GroupsLogicDecodingResult.Success(groupsRuleCreator) =>
          NonEmptyList.fromList(usersDefinitions.items) match {
            case Some(userDefs) =>
              val groupsRule = groupsRuleCreator(userDefs)
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
    NonEmptyList[UserDef] => GroupsRule[GroupsLogic],
    NonEmptyList[UserDef] => GroupsRule[PositiveGroupsLogic],
    NonEmptyList[UserDef] => GroupsRule[NegativeGroupsLogic],
    NonEmptyList[UserDef] => GroupsRule[GroupsLogic.AllOf],
    NonEmptyList[UserDef] => GroupsRule[GroupsLogic.AnyOf],
    NonEmptyList[UserDef] => GroupsRule[GroupsLogic.NotAllOf],
    NonEmptyList[UserDef] => GroupsRule[GroupsLogic.NotAnyOf],
  ](createCombinedGroupsRule)

  private def createCombinedGroupsRule(positive: NonEmptyList[UserDef] => GroupsRule[PositiveGroupsLogic],
                                       negative: NonEmptyList[UserDef] => GroupsRule[NegativeGroupsLogic]) = {
    (userDefs: NonEmptyList[UserDef]) => {
      val positiveLogic = positive(userDefs).settings.permittedGroupsLogic
      val negativeLogic = negative(userDefs).settings.permittedGroupsLogic
      val logic = RuntimeResolvableGroupsLogic.Combined(positiveLogic, negativeLogic)
      val settings = GroupsRule.Settings(logic, userDefs)
      new CombinedGroupsRule(ev.name, settings)(globalSettings.userIdCaseSensitivity)
    }
  }

  private implicit def runtimeResolvableGroupsLogic[GL <: GroupsLogic : GroupsLogic.Creator: GroupsRule.Creator]: Decoder[NonEmptyList[UserDef] => GroupsRule[GL]] = {
    DecoderHelpers
      .decoderStringLikeOrUniqueNonEmptyList[RuntimeMultiResolvableVariable[GroupIdLike]]
      .map(RuntimeResolvableGroupsLogic.Simple[GL](_))
      .map{ logic =>
        (userDefs: NonEmptyList[UserDef]) => {
          val settings = GroupsRule.Settings(logic, userDefs)
          GroupsRule.Creator[GL].create(ev.name, settings, globalSettings.userIdCaseSensitivity)
        }
      }
  }

}
