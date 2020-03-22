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
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef
import tech.beshu.ror.accesscontrol.blocks.rules.GroupsRule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleWithVariableUsageDefinition
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.domain.Group
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.common._
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.utils.CirceOps._

import scala.collection.SortedSet

class GroupsRuleDecoder(usersDefinitions: Definitions[UserDef])
  extends RuleDecoderWithoutAssociatedFields[GroupsRule](
  DecoderHelpers
    .decoderStringLikeOrUniqueNonEmptyList[RuntimeMultiResolvableVariable[Group]]
    .toSyncDecoder
    .mapError(RulesLevelCreationError.apply)
    .emapE { groups =>
      NonEmptySet.fromSet(SortedSet.empty[UserDef] ++ usersDefinitions.items) match {
        case Some(userDefs) => Right(RuleWithVariableUsageDefinition.create(new GroupsRule(GroupsRule.Settings(groups, userDefs))))
        case None => Left(RulesLevelCreationError(Message(s"No user definitions was defined. Rule `${GroupsRule.name.show}` requires them.")))
      }
    }
    .decoder
)
