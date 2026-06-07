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
import tech.beshu.ror.accesscontrol.blocks.BlockContext.*
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause
import tech.beshu.ror.accesscontrol.blocks.Decision.{Permitted, Denied}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleName}
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.RepositoriesRule.{AllowedRepositories, Settings}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater, Decision}
import tech.beshu.ror.accesscontrol.domain.RepositoryName
import tech.beshu.ror.accesscontrol.matchers.ZeroKnowledgeRepositoryFilterScalaAdapter.CheckResult
import tech.beshu.ror.accesscontrol.matchers.{PatternsMatcher, ZeroKnowledgeRepositoryFilterScalaAdapter}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.{resolveAll, staticallyResolvedValues}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.{RequestIdAwareLogging, ZeroKnowledgeIndexFilter}

class RepositoriesRule(val settings: Settings)
  extends RegularRule
    with RequestIdAwareLogging {

  override val name: Rule.Name = RepositoriesRule.Name.name

  private val zeroKnowledgeMatchFilter = new ZeroKnowledgeRepositoryFilterScalaAdapter(new ZeroKnowledgeIndexFilter(true))

  // Built once when the allowed repositories are statically configured (no runtime
  // variables); otherwise the matcher (and the wildcard short-circuit) is computed
  // per request from the resolved values. When static, the per-request `resolveAll`
  // is bypassed entirely (matching `UsersRule`).
  private val staticAllowedRepositories: Option[AllowedRepositories] =
    staticallyResolvedValues(settings.allowedRepositories.toNonEmptyList)
      .map(values => AllowedRepositories.from(values.toCovariantSet))

  override def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] = Task {
    BlockContextUpdater[B] match {
      case BlockContextUpdater.RepositoryRequestBlockContextUpdater =>
        checkRepositories(blockContext)
      case BlockContextUpdater.SnapshotRequestBlockContextUpdater =>
        checkSnapshotRepositories(blockContext)
      case _ =>
        Permitted(blockContext)
    }
  }

  private def allowedRepositoriesFor(blockContext: BlockContext): AllowedRepositories =
    staticAllowedRepositories.getOrElse {
      AllowedRepositories.from(resolveAll(settings.allowedRepositories.toNonEmptyList, blockContext).toCovariantSet)
    }

  private def checkRepositories[B <: BlockContext](blockContext: RepositoryRequestBlockContext)
                                                  (implicit ev: RepositoryRequestBlockContext <:< B): Decision[B] = {
    checkAllowedRepositories(
      allowedRepositoriesFor(blockContext),
      blockContext.repositories,
      blockContext.requestContext
    ) match {
      case Right(filteredRepositories) => Permitted(blockContext.withRepositories(filteredRepositories))
      case Left(_) => Denied(Cause.NotAuthorized)
    }
  }

  private def checkSnapshotRepositories[B <: BlockContext](blockContext: SnapshotRequestBlockContext)
                                                          (implicit ev: SnapshotRequestBlockContext <:< B): Decision[B] = {
    checkAllowedRepositories(
      allowedRepositoriesFor(blockContext),
      blockContext.repositories,
      blockContext.requestContext
    ) match {
      case Right(filteredRepositories) => Permitted(blockContext.withRepositories(filteredRepositories))
      case Left(_) => Denied(Cause.NotAuthorized)
    }
  }

  private def checkAllowedRepositories(allowedRepositories: AllowedRepositories,
                                       repositoriesToCheck: Set[RepositoryName],
                                       requestContext: RequestContext) = {
    implicit val requestContextImpl: RequestContext = requestContext
    if (allowedRepositories.hasWildcard) {
      Right(repositoriesToCheck)
    } else {
      zeroKnowledgeMatchFilter.check(
        repositoriesToCheck,
        allowedRepositories.matcher
      ) match {
        case CheckResult.Ok(processedRepositories) if requestContext.isReadOnlyRequest =>
          Right(processedRepositories)
        case CheckResult.Ok(processedRepositories) if processedRepositories.size === repositoriesToCheck.size =>
          Right(processedRepositories)
        case CheckResult.Ok(processedRepositories) =>
          val filteredOutRepositories = repositoriesToCheck.diff(processedRepositories).map(_.show)
          logger.debug(
            s"Write request with repositories cannot proceed because some of the repositories " +
              s"[${filteredOutRepositories.show}] were filtered out by ACL. The request will be rejected.."
          )
          Left(())
        case CheckResult.Failed =>
          logger.debug(s"The processed repositories do not match the allowed repositories. The request will be rejected..")
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

  // Bundles the wildcard short-circuit flag with the matcher so both can be precomputed
  // once for static configurations. The matcher is lazy so the wildcard path (which
  // short-circuits before matching) never pays for building it.
  private final class AllowedRepositories(val hasWildcard: Boolean, allowedRepositories: Set[RepositoryName]) {
    lazy val matcher: PatternsMatcher[RepositoryName] = PatternsMatcher.create(allowedRepositories)
  }
  private object AllowedRepositories {
    def from(allowedRepositories: Set[RepositoryName]): AllowedRepositories = {
      val hasWildcard = allowedRepositories.contains(RepositoryName.all) || allowedRepositories.contains(RepositoryName.wildcard)
      new AllowedRepositories(hasWildcard, allowedRepositories)
    }
  }
}