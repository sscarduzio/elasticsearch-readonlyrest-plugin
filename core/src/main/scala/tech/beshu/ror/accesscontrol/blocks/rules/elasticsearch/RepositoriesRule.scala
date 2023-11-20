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
import cats.implicits._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.BlockContext._
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.RepositoriesRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleName, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.RepositoryName
import tech.beshu.ror.accesscontrol.matchers.ZeroKnowledgeRepositoryFilterScalaAdapter.CheckResult
import tech.beshu.ror.accesscontrol.matchers.{PatternsMatcher, ZeroKnowledgeRepositoryFilterScalaAdapter}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.resolveAll
import tech.beshu.ror.utils.ZeroKnowledgeIndexFilter

class RepositoriesRule(val settings: Settings)
  extends RegularRule
    with Logging {

  override val name: Rule.Name = RepositoriesRule.Name.name

  private val zeroKnowledgeMatchFilter = new ZeroKnowledgeRepositoryFilterScalaAdapter(new ZeroKnowledgeIndexFilter(true))

  override def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = Task {
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
      zeroKnowledgeMatchFilter.check(
        repositoriesToCheck,
        PatternsMatcher.create(allowedRepositories)
      ) match {
        case CheckResult.Ok(processedRepositories) if requestContext.isReadOnlyRequest =>
          Right(processedRepositories)
        case CheckResult.Ok(processedRepositories) if processedRepositories.size === repositoriesToCheck.size =>
          Right(processedRepositories)
        case CheckResult.Ok(processedRepositories) =>
          val filteredOutRepositories = repositoriesToCheck.diff(processedRepositories).map(_.show)
          logger.debug(
            s"[${requestContext.id.show}] Write request with repositories cannot proceed because some of the repositories " +
              s"[${filteredOutRepositories.toList.mkString_(",")}] were filtered out by ACL. The request will be rejected.."
          )
          Left(())
        case CheckResult.Failed =>
          logger.debug(s"[${requestContext.id.show}] The processed repositories do not match the allowed repositories. The request will be rejected..")
          Left(())
      }
    }
  }
}

object RepositoriesRule {

  implicit case object Name extends RuleName[RepositoriesRule] {
    override val name = Rule.Name("repositories")
  }

  final case class Settings(allowedRepositories: NonEmptySet[RuntimeMultiResolvableVariable[RepositoryName]])
}