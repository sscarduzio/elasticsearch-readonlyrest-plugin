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
package tech.beshu.ror.accesscontrol.blocks.variables.runtime

import cats.data.NonEmptyList
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.domain.GroupsLogic.{NegativeGroupsLogic, PositiveGroupsLogic}
import tech.beshu.ror.accesscontrol.domain.{GroupIdLike, GroupIds, GroupsLogic}
import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.resolveAll
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

trait RuntimeResolvableGroupsLogic[+GL <: GroupsLogic] {
  def resolve[B <: BlockContext](blockContext: B): Option[GL]

  def usedVariables: NonEmptyList[RuntimeMultiResolvableVariable[GroupIdLike]]
}

object RuntimeResolvableGroupsLogic {

  type RESOLVABLE_GROUP_IDS = UniqueNonEmptyList[RuntimeMultiResolvableVariable[GroupIdLike]]

  final class Simple[+GL <: GroupsLogic : GroupsLogic.Creator](val groupIds: RESOLVABLE_GROUP_IDS) extends RuntimeResolvableGroupsLogic[GL] {
    override def resolve[B <: BlockContext](blockContext: B): Option[GL] = {
      UniqueNonEmptyList
        .from(resolveAll(groupIds.toNonEmptyList, blockContext))
        .map(GroupIds.apply)
        .map(GroupsLogic.Creator[GL].create)
    }

    override def usedVariables: NonEmptyList[RuntimeMultiResolvableVariable[GroupIdLike]] = groupIds.toNonEmptyList
  }

  final class Combined(val positive: RuntimeResolvableGroupsLogic[PositiveGroupsLogic],
                       val negative: RuntimeResolvableGroupsLogic[NegativeGroupsLogic]) extends RuntimeResolvableGroupsLogic[GroupsLogic.Combined] {
    override def resolve[B <: BlockContext](blockContext: B): Option[GroupsLogic.Combined] = {
      for {
        permitted <- positive.resolve(blockContext)
        forbidden <- negative.resolve(blockContext)
      } yield GroupsLogic.Combined(permitted, forbidden)
    }

    override def usedVariables: NonEmptyList[RuntimeMultiResolvableVariable[GroupIdLike]] =
      positive.usedVariables ::: negative.usedVariables
  }
}
