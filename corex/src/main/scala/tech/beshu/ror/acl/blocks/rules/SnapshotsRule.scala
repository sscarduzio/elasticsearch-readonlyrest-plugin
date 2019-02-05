package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import tech.beshu.ror.acl.aDomain.{Action, IndexName}
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.BaseSpecializedIndicesRule.Settings
import tech.beshu.ror.acl.request.RequestContext

class SnapshotsRule(override val settings: Settings)
  extends BaseSpecializedIndicesRule(settings) {

  override val name: Rule.Name = SnapshotsRule.name

  override protected def isSpecializedIndexAction(action: Action): Boolean = action.isSnapshotAction

  override protected def specializedIndicesFromRequest(request: RequestContext): Set[IndexName] = request.snapshots

  override protected def blockContextWithSpecializedIndices(blockContext: BlockContext,
                                                            indices: NonEmptySet[IndexName]): BlockContext =
    blockContext.withSnapshots(indices)

}

object SnapshotsRule {
  val name = Rule.Name("snapshots")
}