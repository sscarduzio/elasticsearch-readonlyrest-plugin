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
import cats.implicits.*
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.BlockContext.SnapshotRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleName, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.SnapshotsRule.Settings
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.SnapshotName
import tech.beshu.ror.accesscontrol.matchers.ZeroKnowledgeMatchFilterScalaAdapter.AlterResult.{Altered, NotAltered}
import tech.beshu.ror.accesscontrol.matchers.{PatternsMatcher, ZeroKnowledgeMatchFilterScalaAdapter}
import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.resolveAll
import tech.beshu.ror.syntax.*

class SnapshotsRule(val settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = SnapshotsRule.Name.name

  private val zeroKnowledgeMatchFilter = new ZeroKnowledgeMatchFilterScalaAdapter

  override def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = Task {
    BlockContextUpdater[B] match {
      case BlockContextUpdater.CurrentUserMetadataRequestBlockContextUpdater =>
        Fulfilled(blockContext)
      case BlockContextUpdater.SnapshotRequestBlockContextUpdater =>
        checkAllowedSnapshots(
          resolveAll(settings.allowedSnapshots.toNonEmptyList, blockContext).toCovariantSet,
          blockContext
        )
      case _ =>
        Fulfilled(blockContext)
    }
  }

  private def checkAllowedSnapshots[B <: BlockContext](allowedSnapshots: Set[SnapshotName],
                                                       blockContext: SnapshotRequestBlockContext)
                                                      (implicit ev: SnapshotRequestBlockContext <:< B): RuleResult[B] = {
    if (allowedSnapshots.contains(SnapshotName.All) || allowedSnapshots.contains(SnapshotName.Wildcard)) {
      Fulfilled(blockContext)
    } else {
      zeroKnowledgeMatchFilter.alterSnapshotsIfNecessary(
        blockContext.snapshots,
        PatternsMatcher.create(allowedSnapshots)
      ) match {
        case NotAltered() =>
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

  implicit case object Name extends RuleName[SnapshotsRule] {
    override val name = Rule.Name("snapshots")
  }

  final case class Settings(allowedSnapshots: NonEmptySet[RuntimeMultiResolvableVariable[SnapshotName]])
}