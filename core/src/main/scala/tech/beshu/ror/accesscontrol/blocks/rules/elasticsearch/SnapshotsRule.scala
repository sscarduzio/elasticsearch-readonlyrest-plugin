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
package tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch

import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.BlockContext.SnapshotRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause
import tech.beshu.ror.accesscontrol.blocks.Decision.{Denied, Permitted}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleName}
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.SnapshotsRule.{AllowedSnapshots, Settings}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater, Decision}
import tech.beshu.ror.accesscontrol.domain.SnapshotName
import tech.beshu.ror.accesscontrol.matchers.ZeroKnowledgeMatchFilterScalaAdapter.AlterResult.{Altered, NotAltered}
import tech.beshu.ror.accesscontrol.matchers.{PatternsMatcher, ZeroKnowledgeMatchFilterScalaAdapter}
import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.{resolveAll, resolveAllIfPreResolved}
import tech.beshu.ror.syntax.*

class SnapshotsRule(val settings: Settings) extends RegularRule {

  override val name: Rule.Name = SnapshotsRule.Name.name

  private val zeroKnowledgeMatchFilter = new ZeroKnowledgeMatchFilterScalaAdapter

  // Optimization: when the allowed snapshots are pre-resolved, build the matcher once instead of
  // per request.
  private val staticAllowedSnapshots: Option[AllowedSnapshots] =
    resolveAllIfPreResolved(settings.allowedSnapshots.toNonEmptyList)
      .map(snapshots => AllowedSnapshots.from(snapshots.toList.toCovariantSet))

  override def regularCheck[B <: BlockContext: BlockContextUpdater](blockContext: B): Task[Decision[B]] = Task {
    BlockContextUpdater[B] match {
      case BlockContextUpdater.UserMetadataRequestBlockContextUpdater =>
        Permitted(blockContext)
      case BlockContextUpdater.SnapshotRequestBlockContextUpdater =>
        val allowedSnapshots = staticAllowedSnapshots.getOrElse {
          AllowedSnapshots.from(resolveAll(settings.allowedSnapshots.toNonEmptyList, blockContext).toCovariantSet)
        }
        checkAllowedSnapshots(allowedSnapshots, blockContext)
      case _ =>
        Permitted(blockContext)
    }
  }

  private def checkAllowedSnapshots[B <: BlockContext](
      allowedSnapshots: AllowedSnapshots,
      blockContext: SnapshotRequestBlockContext
  )(
      implicit ev: SnapshotRequestBlockContext <:< B
  ): Decision[B] = {
    if (allowedSnapshots.hasWildcard) {
      Permitted(blockContext)
    } else {
      zeroKnowledgeMatchFilter.alterSnapshotsIfNecessary(
        blockContext.snapshots,
        allowedSnapshots.matcher
      ) match {
        case NotAltered() =>
          Permitted(blockContext)
        case Altered(filteredSnapshots)
            if filteredSnapshots.nonEmpty && blockContext.requestContext.isReadOnlyRequest =>
          Permitted(blockContext.withSnapshots(filteredSnapshots))
        case Altered(_) =>
          Denied(Cause.NotAuthorized)
      }
    }
  }

}

object SnapshotsRule {

  implicit case object Name extends RuleName[SnapshotsRule] {
    override val name = Rule.Name("snapshots")
  }

  final case class Settings(allowedSnapshots: NonEmptySet[RuntimeMultiResolvableVariable[SnapshotName]])

  // The matcher is lazy so the wildcard path (which short-circuits before matching) never builds it.
  private final class AllowedSnapshots private (val hasWildcard: Boolean, allowedSnapshots: Set[SnapshotName]) {
    lazy val matcher: PatternsMatcher[SnapshotName] = PatternsMatcher.create(allowedSnapshots)
  }

  private object AllowedSnapshots {

    def from(allowedSnapshots: Set[SnapshotName]): AllowedSnapshots = {
      val hasWildcard = allowedSnapshots.contains(SnapshotName.All) || allowedSnapshots.contains(SnapshotName.Wildcard)
      new AllowedSnapshots(hasWildcard, allowedSnapshots)
    }

  }

}
