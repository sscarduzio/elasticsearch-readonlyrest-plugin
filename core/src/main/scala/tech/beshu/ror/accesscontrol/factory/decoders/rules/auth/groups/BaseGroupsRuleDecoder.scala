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

import cats.data.NonEmptyList
import cats.implicits.*
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.*
import tech.beshu.ror.accesscontrol.blocks.rules.auth.AllOfGroupsRule.*
import tech.beshu.ror.accesscontrol.blocks.rules.auth.AnyOfGroupsRule.*
import tech.beshu.ror.accesscontrol.blocks.rules.auth.NotAllOfGroupsRule.*
import tech.beshu.ror.accesscontrol.blocks.rules.auth.NotAnyOfGroupsRule.*
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseGroupsRule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeResolvableGroupsLogic, RuntimeResolvableVariableCreator}
import tech.beshu.ror.accesscontrol.domain.GroupsLogic.{NegativeGroupsLogic, PositiveGroupsLogic}
import tech.beshu.ror.accesscontrol.domain.{CaseSensitivity, GroupIdLike, GroupsLogic}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.common.*
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions
import tech.beshu.ror.accesscontrol.factory.decoders.rules.auth.groups.BaseGroupsRuleDecoder.*
import tech.beshu.ror.accesscontrol.utils.CirceOps.*

private[auth] class BaseGroupsRuleDecoder(implicit ruleName: Rule.Name,
                                          usersDefinitions: Definitions[UserDef],
                                          userIdCaseSensitivity: CaseSensitivity,
                                          variableCreator: RuntimeResolvableVariableCreator)
  extends GroupsLogicRepresentationDecoder[
    Either[RulesLevelCreationError, BaseGroupsRule[GroupsLogic]],
    Either[RulesLevelCreationError, BaseGroupsRule[PositiveGroupsLogic]],
    Either[RulesLevelCreationError, BaseGroupsRule[NegativeGroupsLogic]],
    Either[RulesLevelCreationError, BaseGroupsRule[GroupsLogic.AllOf]],
    Either[RulesLevelCreationError, BaseGroupsRule[GroupsLogic.AnyOf]],
    Either[RulesLevelCreationError, BaseGroupsRule[GroupsLogic.NotAllOf]],
    Either[RulesLevelCreationError, BaseGroupsRule[GroupsLogic.NotAnyOf]],
  ](createCombinedGroupsRule)

private[auth] object BaseGroupsRuleDecoder {

  private def createCombinedGroupsRule(positive: Either[RulesLevelCreationError, BaseGroupsRule[PositiveGroupsLogic]],
                                       negative: Either[RulesLevelCreationError, BaseGroupsRule[NegativeGroupsLogic]])
                                      (implicit ruleName: Rule.Name,
                                       usersDefinitions: Definitions[UserDef],
                                       userIdCaseSensitivity: CaseSensitivity): Either[RulesLevelCreationError, CombinedLogicGroupsRule] = {
    for {
      positiveLogic <- positive
      negativeLogic <- negative
      userDefsNel <- userDefs
    } yield {
      val logic = RuntimeResolvableGroupsLogic.Combined(positiveLogic.settings.permittedGroupsLogic, negativeLogic.settings.permittedGroupsLogic)
      val settings = BaseGroupsRule.Settings(logic, userDefsNel)
      new CombinedLogicGroupsRule(settings)(userIdCaseSensitivity)
    }
  }

  implicit def baseGroupsRuleDecoder[
    GL <: GroupsLogic : GroupsLogic.Creator : BaseGroupsRule.Creator
  ](implicit ruleName: Rule.Name,
    usersDefinitions: Definitions[UserDef],
    userIdCaseSensitivity: CaseSensitivity,
    variableCreator: RuntimeResolvableVariableCreator): Decoder[Either[RulesLevelCreationError, BaseGroupsRule[GL]]] = {
    DecoderHelpers
      .decoderStringLikeOrUniqueNonEmptyList[RuntimeMultiResolvableVariable[GroupIdLike]]
      .map(RuntimeResolvableGroupsLogic.Simple[GL](_))
      .map { logic =>
        userDefs.map { userDefs =>
          val settings = BaseGroupsRule.Settings(logic, userDefs)
          BaseGroupsRule.Creator[GL].create(settings, userIdCaseSensitivity)
        }
      }
  }

  private def userDefs(implicit ruleName: Rule.Name,
                       usersDefinitions: Definitions[UserDef]): Either[RulesLevelCreationError, NonEmptyList[UserDef]] = {
    Either.fromOption(
      NonEmptyList.fromList(usersDefinitions.items),
      RulesLevelCreationError(Message(s"No user definitions was defined. Rule `${ruleName.show}` requires them.")),
    )
  }

}
