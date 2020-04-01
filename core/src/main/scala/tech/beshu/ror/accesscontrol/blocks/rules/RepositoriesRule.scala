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

import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.BlockContext.RepositoryRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.RepositoriesRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.utils.ZeroKnowledgeMatchFilterScalaAdapter.AlterResult.{Altered, NotAltered}
import tech.beshu.ror.accesscontrol.blocks.rules.utils.{MatcherWithWildcardsScalaAdapter, ZeroKnowledgeMatchFilterScalaAdapter}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.{Header, IndexName, RepositoryName}
import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.resolveAll
import tech.beshu.ror.utils.MatcherWithWildcards
import BlockContext._
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.request.RequestContext

import scala.collection.JavaConverters._

class RepositoriesRule(val settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = RepositoriesRule.name
  private val zeroKnowledgeMatchFilter = new ZeroKnowledgeMatchFilterScalaAdapter

  override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = Task {
    BlockContextUpdater[B] match {
      case BlockContextUpdater.RepositoryRequestBlockContextUpdater =>
        checkRepositories(blockContext)
      case BlockContextUpdater.SnapshotRequestBlockContextUpdater =>
        checkSnapshotRepositories(blockContext)
      case _ =>
        Fulfilled(blockContext)
    }
  }

  private def checkRepositories(blockContext: RepositoryRequestBlockContext): RuleResult[RepositoryRequestBlockContext] = {
    checkAllowedRepositories(
      resolveAll(settings.allowedRepositories.toNonEmptyList, blockContext).toSet,
      blockContext.repositories,
      blockContext.requestContext
    ) match {
      case Right(filteredRepositories) => Fulfilled(blockContext.withRepositories(filteredRepositories))
      case Left(_) => Rejected()
    }
  }

  private def checkSnapshotRepositories(blockContext: SnapshotRequestBlockContext): RuleResult[SnapshotRequestBlockContext] = {
    checkAllowedRepositories(
      resolveAll(settings.allowedRepositories.toNonEmptyList, blockContext).toSet,
      blockContext.repositories,
      blockContext.requestContext
    ) match {
      case Right(filteredRepositories) => Fulfilled(blockContext.withRepositories(filteredRepositories))
      case Left(_) => Rejected()
    }
  }

  private def checkAllowedRepositories(allowedRepositories: Set[RepositoryName],
                                       repositoriesToCheck: Set[RepositoryName],
                                       requestContext: RequestContext) = {
    if (allowedRepositories.contains(RepositoryName.all) || allowedRepositories.contains(RepositoryName.wildcard)) {
      Right(repositoriesToCheck)
    } else {
      zeroKnowledgeMatchFilter.alterRepositoriesIfNecessary(
        repositoriesToCheck,
        new MatcherWithWildcardsScalaAdapter(new MatcherWithWildcards(allowedRepositories.map(_.value.value).asJava))
      ) match {
        case NotAltered => Right(repositoriesToCheck)
        case Altered(filteredRepositories) if requestContext.isReadOnlyRequest => Right(filteredRepositories)
        case Altered(_) => Left(())
      }
    }
  }
}

object RepositoriesRule {
  val name = Rule.Name("repositories")

  final case class Settings(allowedRepositories: NonEmptySet[RuntimeMultiResolvableVariable[RepositoryName]])
}