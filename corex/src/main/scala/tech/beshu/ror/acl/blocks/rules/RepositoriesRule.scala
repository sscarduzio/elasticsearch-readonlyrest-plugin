package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import tech.beshu.ror.acl.aDomain.{Action, IndexName}
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.BaseSpecializedIndicesRule.Settings
import tech.beshu.ror.acl.request.RequestContext


class RepositoriesRule(override val settings: Settings)
  extends BaseSpecializedIndicesRule(settings) {

  override val name: Rule.Name = RepositoriesRule.name

  override protected def isSpecializedIndexAction(action: Action): Boolean = action.isRepositoryAction

  override protected def specializedIndicesFromRequest(request: RequestContext): Set[IndexName] = request.repositories

  override protected def blockContextWithSpecializedIndices(blockContext: BlockContext,
                                                            indices: NonEmptySet[IndexName]): BlockContext =
    blockContext.withRepositories(indices)

}

object RepositoriesRule {
  val name = Rule.Name("repositories")
}