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
package tech.beshu.ror.accesscontrol.blocks.rules

import cats.implicits._
import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.BlockContext.SnapshotRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.SnapshotsRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.utils.ZeroKnowledgeMatchFilterScalaAdapter.AlterResult.{Altered, NotAltered}
import tech.beshu.ror.accesscontrol.blocks.rules.utils.{MatcherWithWildcardsScalaAdapter, ZeroKnowledgeMatchFilterScalaAdapter}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.SnapshotName
import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.resolveAll
import tech.beshu.ror.utils.MatcherWithWildcards

import scala.collection.JavaConverters._

class SnapshotsRule(val settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = SnapshotsRule.name
  private val zeroKnowledgeMatchFilter = new ZeroKnowledgeMatchFilterScalaAdapter

  override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = Task {
    BlockContextUpdater[B] match {
      case BlockContextUpdater.CurrentUserMetadataRequestBlockContextUpdater =>
        Fulfilled(blockContext)
      case BlockContextUpdater.SnapshotRequestBlockContextUpdater =>
        checkAllowedSnapshots(
          resolveAll(settings.allowedSnapshots.toNonEmptyList, blockContext).toSet,
          blockContext
        )
      case _ =>
        Fulfilled(blockContext)
    }
  }

  private def checkAllowedSnapshots(allowedSnapshots: Set[SnapshotName],
                                    blockContext: SnapshotRequestBlockContext): RuleResult[SnapshotRequestBlockContext] = {
    if (allowedSnapshots.contains(SnapshotName.all) || allowedSnapshots.contains(SnapshotName.wildcard)) {
      Fulfilled(blockContext)
    } else {
      zeroKnowledgeMatchFilter.alterSnapshotsIfNecessary(
        blockContext.snapshots,
        new MatcherWithWildcardsScalaAdapter(new MatcherWithWildcards(allowedSnapshots.map(_.value.value).asJava))
      ) match {
        case NotAltered =>
          Fulfilled(blockContext)
        case Altered(filteredSnapshots) if filteredSnapshots.nonEmpty && blockContext.requestContext.isReadOnlyRequest =>
          Fulfilled(blockContext.withSnapshots(filteredSnapshots))
        case Altered(_) =>
          Rejected()
      }
    }
  }
}

object SnapshotsRule {
  val name = Rule.Name("snapshots")

  final case class Settings(allowedSnapshots: NonEmptySet[RuntimeMultiResolvableVariable[SnapshotName]])
}