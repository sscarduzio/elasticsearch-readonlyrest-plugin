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

import cats.data.NonEmptyList
import cats.implicits._
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleName
import tech.beshu.ror.accesscontrol.blocks.rules.{BaseGroupsRule, GroupsAndRule, GroupsOrRule}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.VariableContext.VariableUsage
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.common._
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.accesscontrol.utils.CirceOps._
import tech.beshu.ror.accesscontrol.blocks.users.LocalUsersContext.LocalUsersSupport
import tech.beshu.ror.accesscontrol.blocks.ImpersonationWarning.ImpersonationWarningSupport.ImpersonationWarningExtractor
import tech.beshu.ror.accesscontrol.domain.GroupLike.GroupName
import tech.beshu.ror.accesscontrol.domain.{GroupLike, PermittedGroups, ResolvablePermittedGroups}

class GroupsOrRuleDecoder(usersDefinitions: Definitions[UserDef],
                          override implicit val caseMappingEquality: UserIdCaseMappingEquality)
  extends BaseGroupsRuleDecoder[GroupsOrRule](usersDefinitions, caseMappingEquality) {

  override protected def createRule(settings: BaseGroupsRule.Settings,
                                    caseMappingEquality: UserIdCaseMappingEquality): GroupsOrRule = {
    new GroupsOrRule(settings, caseMappingEquality)
  }
}

class GroupsAndRuleDecoder(usersDefinitions: Definitions[UserDef],
                           override implicit val caseMappingEquality: UserIdCaseMappingEquality)
  extends BaseGroupsRuleDecoder[GroupsAndRule](usersDefinitions, caseMappingEquality) {

  override protected def createRule(settings: BaseGroupsRule.Settings,
                                    caseMappingEquality: UserIdCaseMappingEquality): GroupsAndRule = {
    new GroupsAndRule(settings, caseMappingEquality)
  }
}

abstract class BaseGroupsRuleDecoder[R <: BaseGroupsRule : RuleName : VariableUsage : LocalUsersSupport : ImpersonationWarningExtractor](usersDefinitions: Definitions[UserDef],
                                                                                                                                         implicit val caseMappingEquality: UserIdCaseMappingEquality)

  extends RuleBaseDecoderWithoutAssociatedFields[R] {

  protected def createRule(settings: BaseGroupsRule.Settings, caseMappingEquality: UserIdCaseMappingEquality): R

  override protected def decoder: Decoder[RuleDefinition[R]] = {
    DecoderHelpers
      .decoderStringLikeOrUniqueNonEmptyList[RuntimeMultiResolvableVariable[GroupLike]]
      .toSyncDecoder
      .mapError(RulesLevelCreationError.apply)
      .emapE { groups =>
        NonEmptyList.fromList(usersDefinitions.items) match {
          case Some(userDefs) =>
            Right(RuleDefinition.create(createRule(
              BaseGroupsRule.Settings(ResolvablePermittedGroups(groups), userDefs),
              caseMappingEquality
            )))
          case None =>
            Left(RulesLevelCreationError(Message(s"No user definitions was defined. Rule `${ruleName.show}` requires them.")))
        }
      }
      .decoder
  }
}
