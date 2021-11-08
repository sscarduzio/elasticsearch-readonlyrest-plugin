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
package tech.beshu.ror.accesscontrol.blocks.rules.indicesrule

import cats.data.NonEmptySet
import cats.implicits._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiIndexRequestBlockContext.Indices
import tech.beshu.ror.accesscontrol.blocks.BlockContext.{AliasRequestBlockContext, HasIndexPacks, SnapshotRequestBlockContext}
import tech.beshu.ror.accesscontrol.blocks.BlockContextUpdater.{AliasRequestBlockContextUpdater, CurrentUserMetadataRequestBlockContextUpdater, FilterableMultiRequestBlockContextUpdater, FilterableRequestBlockContextUpdater, GeneralIndexRequestBlockContextUpdater, GeneralNonIndexRequestBlockContextUpdater, MultiIndexRequestBlockContextUpdater, RepositoryRequestBlockContextUpdater, SnapshotRequestBlockContextUpdater, TemplateRequestBlockContextUpdater}
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleResult.Rejected.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.{RegularRule, RuleName, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.IndicesRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.clusterindices.AllClusterIndices
import tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.templates.AllTemplateIndices
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater, BlockContextWithIndexPacksUpdater, BlockContextWithIndicesUpdater}
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.accesscontrol.matchers.UniqueIdentifierGenerator
import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.resolveAll

class IndicesRule(override val settings: Settings,
                  override val identifierGenerator: UniqueIdentifierGenerator)
  extends RegularRule
    with AllClusterIndices
    with AllTemplateIndices
    with Logging {

  import IndicesRule._

  override val name: Rule.Name = IndicesRule.Name.name

  override def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = {
    BlockContextUpdater[B] match {
      case CurrentUserMetadataRequestBlockContextUpdater => processRequestWithoutIndices(blockContext)
      case GeneralNonIndexRequestBlockContextUpdater => processRequestWithoutIndices(blockContext)
      case RepositoryRequestBlockContextUpdater => processRequestWithoutIndices(blockContext)
      case SnapshotRequestBlockContextUpdater => processSnapshotRequest(blockContext)
      case GeneralIndexRequestBlockContextUpdater => processIndicesRequest(blockContext)
      case FilterableRequestBlockContextUpdater => processIndicesRequest(blockContext)
      case MultiIndexRequestBlockContextUpdater => processIndicesPacks(blockContext)
      case FilterableMultiRequestBlockContextUpdater => processIndicesPacks(blockContext)
      case AliasRequestBlockContextUpdater => processAliasRequest(blockContext)
      case TemplateRequestBlockContextUpdater => processTemplateRequest(blockContext)
    }
  }

  private def processRequestWithoutIndices[B <: BlockContext](blockContext: B): Task[RuleResult[B]] = Task.now {
    if (settings.mustInvolveIndices) Rejected()
    else Fulfilled(blockContext)
  }

  private def processIndicesRequest[B <: BlockContext : BlockContextWithIndicesUpdater](blockContext: B): Task[RuleResult[B]] = {
    if (matchAll) {
      Task.now(Fulfilled(blockContext))
    } else {
      val allAllowedIndices = resolveAll(settings.allowedIndices.toNonEmptyList, blockContext).toSet
      processIndices(blockContext.requestContext, allAllowedIndices, blockContext.indices)
        .map {
          case ProcessResult.Ok(filteredIndices) => Fulfilled(blockContext.withIndices(filteredIndices, allAllowedIndices))
          case ProcessResult.Failed(cause) => Rejected(cause)
        }
    }
  }

  private def processIndicesPacks[B <: BlockContext : BlockContextWithIndexPacksUpdater : HasIndexPacks](blockContext: B): Task[RuleResult[B]] = {
    if (matchAll) {
      Task.now(Fulfilled(blockContext))
    } else {
      import tech.beshu.ror.accesscontrol.blocks.BlockContext.HasIndexPacks._
      def atLeastOneFound(indices: Vector[Indices]) = indices.exists(_.isInstanceOf[Indices.Found])

      val resolvedAllowedIndices = resolveAll(settings.allowedIndices.toNonEmptyList, blockContext).toSet
      blockContext
        .indexPacks
        .foldLeft(Task.now(Vector.empty[Indices].asRight[Option[Cause]])) {
          case (acc, pack) => acc.flatMap {
            case Right(currentList) => pack match {
              case Indices.Found(indices) =>
                processIndices(
                  blockContext.requestContext,
                  resolvedAllowedIndices,
                  indices
                ) map {
                  case ProcessResult.Ok(narrowedIndices) => Right(currentList :+ Indices.Found(narrowedIndices))
                  case ProcessResult.Failed(Some(Cause.IndexNotFound)) => Right(currentList :+ Indices.NotFound)
                  case ProcessResult.Failed(cause) => Left(cause)
                }
              case Indices.NotFound =>
                Task.now(Right(currentList :+ Indices.NotFound))
            }
            case result@Left(_) =>
              Task.now(result)
          }
        }
        .map {
          case Right(indices) if atLeastOneFound(indices) => Fulfilled(blockContext.withIndicesPacks(indices.toList))
          case Right(_) => Rejected(Cause.IndexNotFound)
          case Left(cause) => Rejected(cause)
        }
    }
  }

  private def processAliasRequest(blockContext: AliasRequestBlockContext): Task[RuleResult[AliasRequestBlockContext]] = {
    if (matchAll) {
      Task.now(Fulfilled(blockContext))
    } else {
      val resolvedAllowedIndices = resolveAll(settings.allowedIndices.toNonEmptyList, blockContext).toSet
      for {
        indicesResult <- processIndices(blockContext.requestContext, resolvedAllowedIndices, blockContext.indices)
        aliasesResult <- processIndices(blockContext.requestContext, resolvedAllowedIndices, blockContext.aliases)
      } yield {
        (indicesResult, aliasesResult) match {
          case (ProcessResult.Ok(indices), ProcessResult.Ok(aliases)) =>
            Fulfilled(blockContext.withIndices(indices).withAliases(aliases))
          case (ProcessResult.Failed(cause), _) => Rejected(cause)
          case (_, ProcessResult.Failed(Some(Cause.IndexNotFound))) => Rejected(Some(Cause.AliasNotFound))
          case (_, ProcessResult.Failed(cause)) => Rejected(cause)
        }
      }
    }
  }

  private def processSnapshotRequest(blockContext: SnapshotRequestBlockContext): Task[RuleResult[SnapshotRequestBlockContext]] = {
    if (matchAll) Task.now(Fulfilled(blockContext))
    else if (blockContext.filteredIndices.isEmpty) processRequestWithoutIndices(blockContext)
    else processIndicesRequest(blockContext)
  }

  private val matchAll = settings.allowedIndices.exists {
    case AlreadyResolved(indices) if indices.exists(_.allIndicesRequested) => true
    case _ => false
  }

}

object IndicesRule {

  implicit case object Name extends RuleName[IndicesRule] {
    override val name = Rule.Name("indices")
  }

  final case class Settings(allowedIndices: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]],
                            mustInvolveIndices: Boolean)

  private[indicesrule] sealed trait ProcessResult[+T <: ClusterIndexName]
  private[indicesrule] object ProcessResult {
    final case class Ok[T <: ClusterIndexName](indices: Set[T]) extends ProcessResult[T]
    final case class Failed(cause: Option[Cause]) extends ProcessResult[Nothing]
  }

}