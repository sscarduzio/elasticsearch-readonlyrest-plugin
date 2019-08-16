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
package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import tech.beshu.ror.acl.domain.{Action, IndexName}
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.BaseSpecializedIndicesRule.Settings
import tech.beshu.ror.acl.request.RequestContext


class RepositoriesRule(override val settings: Settings)
  extends BaseSpecializedIndicesRule(settings) {

  override val name: Rule.Name = RepositoriesRule.name

  override protected def isSpecializedIndexAction(action: Action): Boolean = action.isRepository

  override protected def specializedIndicesFromRequest(request: RequestContext): Set[IndexName] = request.repositories

  override protected def blockContextWithSpecializedIndices(blockContext: BlockContext,
                                                            indices: NonEmptySet[IndexName]): BlockContext =
    blockContext.withRepositories(indices.toSortedSet)

}

object RepositoriesRule {
  val name = Rule.Name("repositories")
}