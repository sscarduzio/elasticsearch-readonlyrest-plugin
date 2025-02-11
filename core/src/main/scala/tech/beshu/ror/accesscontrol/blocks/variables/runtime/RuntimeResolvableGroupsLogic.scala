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
import tech.beshu.ror.accesscontrol.domain.{GroupIdLike, GroupIds, GroupsLogic}
import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.resolveAll
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

trait RuntimeResolvableGroupsLogic[GL <: GroupsLogic] {
  def resolve[B <: BlockContext](blockContext: B): Option[GL]

  def usedVariables: NonEmptyList[RuntimeMultiResolvableVariable[GroupIdLike]]
}

object RuntimeResolvableGroupsLogic {
  class Simple[GL <: GroupsLogic](val groupIds: UniqueNonEmptyList[RuntimeMultiResolvableVariable[GroupIdLike]],
                                  creator: GroupIds => GL) extends RuntimeResolvableGroupsLogic[GL] {
    override def resolve[B <: BlockContext](blockContext: B): Option[GL] = {
      UniqueNonEmptyList
        .from(resolveAll(groupIds.toNonEmptyList, blockContext))
        .map(GroupIds.apply)
        .map(creator)
    }

    override def usedVariables: NonEmptyList[RuntimeMultiResolvableVariable[GroupIdLike]] = groupIds.toNonEmptyList
  }

  class Combined[GL <: GroupsLogic](val permittedGroupIds: UniqueNonEmptyList[RuntimeMultiResolvableVariable[GroupIdLike]],
                                    val forbiddenGroupIds: UniqueNonEmptyList[RuntimeMultiResolvableVariable[GroupIdLike]],
                                    creator: (GroupIds, GroupIds) => GL) extends RuntimeResolvableGroupsLogic[GL] {
    override def resolve[B <: BlockContext](blockContext: B): Option[GL] = {
      for {
        permitted <- UniqueNonEmptyList.from(resolveAll(permittedGroupIds.toNonEmptyList, blockContext)).map(GroupIds.apply)
        forbidden <- UniqueNonEmptyList.from(resolveAll(forbiddenGroupIds.toNonEmptyList, blockContext)).map(GroupIds.apply)
      } yield creator(permitted, forbidden)
    }

    override def usedVariables: NonEmptyList[RuntimeMultiResolvableVariable[GroupIdLike]] =
      permittedGroupIds.toNonEmptyList ::: forbiddenGroupIds.toNonEmptyList
  }
}
