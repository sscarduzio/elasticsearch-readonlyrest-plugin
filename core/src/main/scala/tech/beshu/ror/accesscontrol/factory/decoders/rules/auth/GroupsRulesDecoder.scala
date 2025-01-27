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
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.ImpersonationWarning.ImpersonationWarningSupport.ImpersonationWarningExtractor
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleName
import tech.beshu.ror.accesscontrol.blocks.rules.auth.*
import tech.beshu.ror.accesscontrol.blocks.users.LocalUsersContext.LocalUsersSupport
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.VariableContext.VariableUsage
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.{RuntimeMultiResolvableVariable, RuntimeResolvableVariableCreator}
import tech.beshu.ror.accesscontrol.domain.ResolvableGroupsLogic.*
import tech.beshu.ror.accesscontrol.domain.{GroupIdLike, ResolvableGroupIds, ResolvableGroupsLogic}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.common.*
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.utils.CirceOps.*

class GroupsOrRuleDecoder(usersDefinitions: Definitions[UserDef],
                          globalSettings: GlobalSettings,
                          override implicit val variableCreator: RuntimeResolvableVariableCreator)
                         (implicit ev: RuleName[GroupsOrRule])
  extends BaseGroupsRuleDecoder[Or, GroupsOrRule](usersDefinitions, variableCreator) {

  override protected def createRule(settings: BaseGroupsRule.Settings[Or]): GroupsOrRule = {
    new GroupsOrRule(settings, globalSettings.userIdCaseSensitivity)
  }

  protected def createResolvableGroupsLogic(resolvableGroupIds: ResolvableGroupIds): Or = {
    Or(resolvableGroupIds)
  }
}

class GroupsAndRuleDecoder(usersDefinitions: Definitions[UserDef],
                           globalSettings: GlobalSettings,
                           override implicit val variableCreator: RuntimeResolvableVariableCreator)
  extends BaseGroupsRuleDecoder[And, GroupsAndRule](usersDefinitions, variableCreator) {

  override protected def createRule(settings: BaseGroupsRule.Settings[And]): GroupsAndRule = {
    new GroupsAndRule(settings, globalSettings.userIdCaseSensitivity)
  }

  protected def createResolvableGroupsLogic(resolvableGroupIds: ResolvableGroupIds): And = {
    And(resolvableGroupIds)
  }
}

class GroupsNotAllOfRuleDecoder(usersDefinitions: Definitions[UserDef],
                                globalSettings: GlobalSettings,
                                override implicit val variableCreator: RuntimeResolvableVariableCreator)
  extends BaseGroupsRuleDecoder[NotAllOf, GroupsNotAllOfRule](usersDefinitions, variableCreator) {

  override protected def createRule(settings: BaseGroupsRule.Settings[NotAllOf]): GroupsNotAllOfRule = {
    new GroupsNotAllOfRule(settings, globalSettings.userIdCaseSensitivity)
  }

  protected def createResolvableGroupsLogic(resolvableGroupIds: ResolvableGroupIds): NotAllOf = {
    NotAllOf(resolvableGroupIds)
  }
}

class GroupsNotAnyOfRuleDecoder(usersDefinitions: Definitions[UserDef],
                                globalSettings: GlobalSettings,
                                override implicit val variableCreator: RuntimeResolvableVariableCreator)
  extends BaseGroupsRuleDecoder[NotAnyOf, GroupsNotAnyOfRule](usersDefinitions, variableCreator) {

  override protected def createRule(settings: BaseGroupsRule.Settings[NotAnyOf]): GroupsNotAnyOfRule = {
    new GroupsNotAnyOfRule(settings, globalSettings.userIdCaseSensitivity)
  }

  protected def createResolvableGroupsLogic(resolvableGroupIds: ResolvableGroupIds): NotAnyOf = {
    NotAnyOf(resolvableGroupIds)
  }
}

abstract class BaseGroupsRuleDecoder[
  LOGIC <: ResolvableGroupsLogic,
  R <: BaseGroupsRule[LOGIC] : VariableUsage : LocalUsersSupport : ImpersonationWarningExtractor
](
   usersDefinitions: Definitions[UserDef],
   implicit val variableCreator: RuntimeResolvableVariableCreator
 )(implicit ev: RuleName[R])

  extends RuleBaseDecoderWithoutAssociatedFields[R] {

  protected def createRule(settings: BaseGroupsRule.Settings[LOGIC]): R

  protected def createResolvableGroupsLogic(resolvableGroupIds: ResolvableGroupIds): LOGIC

  override protected def decoder: Decoder[RuleDefinition[R]] = {
    DecoderHelpers
      .decoderStringLikeOrUniqueNonEmptyList[RuntimeMultiResolvableVariable[GroupIdLike]]
      .toSyncDecoder
      .mapError(RulesLevelCreationError.apply)
      .emapE { groups =>
        NonEmptyList.fromList(usersDefinitions.items) match {
          case Some(userDefs) =>
            Right(RuleDefinition.create(
              createRule(
                BaseGroupsRule.Settings(createResolvableGroupsLogic(ResolvableGroupIds(groups)), userDefs)
              )
            ))
          case None =>
            Left(RulesLevelCreationError(Message(s"No user definitions was defined. Rule `${ruleName.show}` requires them.")))
        }
      }
      .decoder
  }
}
