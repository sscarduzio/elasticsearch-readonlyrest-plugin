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
package tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices

import cats.data.NonEmptySet
import cats.implicits.*
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.BlockContext.*
import tech.beshu.ror.accesscontrol.blocks.BlockContext.DataStreamRequestBlockContext.BackingIndices
import tech.beshu.ror.accesscontrol.blocks.BlockContext.HasIndexPacks.{indexPacksFromFilterableMultiBlockContext, indexPacksFromMultiIndexBlockContext}
import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiIndexRequestBlockContext.Indices
import tech.beshu.ror.accesscontrol.blocks.BlockContextUpdater.*
import tech.beshu.ror.accesscontrol.blocks.BlockContextWithIndexPacksUpdater.{FilterableMultiRequestBlockContextWithIndexPacksUpdater, MultiIndexRequestBlockContextWithIndexPacksUpdater}
import tech.beshu.ror.accesscontrol.blocks.BlockContextWithIndicesUpdater.{FilterableRequestBlockContextWithIndicesUpdater, GeneralIndexRequestBlockContextWithIndicesUpdater}
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause
import tech.beshu.ror.accesscontrol.blocks.Decision.{Permitted, Denied}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleName}
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.IndicesRule.*
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.clusterindices.AllClusterIndices
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.templates.AllTemplateIndices
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.blocks.*
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote.ClusterName
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestId, RequestedIndex}
import tech.beshu.ror.accesscontrol.matchers.{PatternsMatcher, UniqueIdentifierGenerator}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.resolveAll
import tech.beshu.ror.syntax.*

class IndicesRule(override val settings: Settings,
                  override val identifierGenerator: UniqueIdentifierGenerator)
  extends RegularRule
    with AllClusterIndices
    with AllTemplateIndices {

  import IndicesRule.*

  override val name: Rule.Name = IndicesRule.Name.name

  override def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] = {
    BlockContextUpdater[B] match {
      case UserMetadataRequestBlockContextUpdater => processRequestWithoutIndices(blockContext)
      case GeneralNonIndexRequestBlockContextUpdater => processRequestWithoutIndices(blockContext)
      case RepositoryRequestBlockContextUpdater => processRequestWithoutIndices(blockContext)
      case SnapshotRequestBlockContextUpdater => processSnapshotRequest(blockContext)
      case DataStreamRequestBlockContextUpdater => processDataStreamRequest(blockContext)
      case GeneralIndexRequestBlockContextUpdater => processIndicesRequest(blockContext)
      case FilterableRequestBlockContextUpdater => processIndicesRequest(blockContext)
      case MultiIndexRequestBlockContextUpdater => processIndicesPacks(blockContext)
      case FilterableMultiRequestBlockContextUpdater => processIndicesPacks(blockContext)
      case AliasRequestBlockContextUpdater => processAliasRequest(blockContext)
      case TemplateRequestBlockContextUpdater => processTemplateRequest(blockContext)
      case RorApiRequestBlockContextUpdater => processRequestWithoutIndices(blockContext)
    }
  }

  private def processRequestWithoutIndices[B <: BlockContext](blockContext: B): Task[Decision[B]] = Task.now {
    if (settings.mustInvolveIndices) reject()
    else Permitted(blockContext)
  }

  private def processIndicesRequest[B <: BlockContext : BlockContextWithIndicesUpdater](blockContext: B): Task[Decision[B]] = {
    if (matchAll) {
      Task.now(Permitted(blockContext))
    } else {
      val allAllowedIndices = resolveAll(settings.allowedIndices.toNonEmptyList, blockContext).toCovariantSet
      processIndices(blockContext.requestContext, allAllowedIndices, blockContext.indices, kibanaIndexFrom(blockContext))
        .map {
          case ProcessResult.Ok(filteredIndices) =>
            val allowedClusters = getAllowedClusterNames(blockContext.requestContext, allAllowedIndices)
            Permitted(blockContext.withIndices(filteredIndices, allAllowedIndices).withClusters(allowedClusters))
          case ProcessResult.Failed.IndexNotFound =>
            val allowedClusters = getAllowedClusterNames(blockContext.requestContext, allAllowedIndices)
            Denied(Cause.IndexNotFound(allowedClusters))
          case ProcessResult.Failed.Other =>
            reject()
        }
    }
  }

  private def processIndicesPacks[B <: BlockContext : BlockContextWithIndexPacksUpdater : HasIndexPacks](blockContext: B): Task[Decision[B]] = {
    if (matchAll) {
      Task.now(Permitted(blockContext))
    } else {
      import tech.beshu.ror.accesscontrol.blocks.BlockContext.HasIndexPacks.*
      def atLeastOneFound(indices: List[Indices]) = indices.exists(_.isInstanceOf[Indices.Found])

      val resolvedAllowedIndices = resolveAll(settings.allowedIndices.toNonEmptyList, blockContext).toCovariantSet
      blockContext
        .indexPacks
        .foldLeft(Task.now(List.empty[Indices].asRight[Unit])) {
          case (acc, pack) => acc.flatMap {
            case Right(currentList) => pack match {
              case Indices.Found(indices) =>
                processIndices(
                  blockContext.requestContext,
                  resolvedAllowedIndices,
                  indices,
                  kibanaIndexFrom(blockContext)
                ) map {
                  case ProcessResult.Ok(narrowedIndices) => Right(Indices.Found(narrowedIndices) :: currentList)
                  case ProcessResult.Failed.IndexNotFound => Right(Indices.NotFound :: currentList)
                  case ProcessResult.Failed.Other => Left(())
                }
              case Indices.NotFound =>
                Task.now(Right(Indices.NotFound :: currentList))
            }
            case result@Left(_) =>
              Task.now(result)
          }
        }
        .map {
          case Right(indices) if atLeastOneFound(indices) => Permitted(blockContext.withIndicesPacks(indices.reverse))
          case Right(_) => Denied(Cause.IndexNotFound(
            getAllowedClusterNames(blockContext.requestContext, resolvedAllowedIndices)
          ))
          case Left(_) => reject()
        }
    }
  }

  private def processAliasRequest(blockContext: AliasRequestBlockContext): Task[Decision[AliasRequestBlockContext]] = {
    if (matchAll) {
      Task.now(Permitted(blockContext))
    } else {
      val resolvedAllowedIndices = resolveAll(settings.allowedIndices.toNonEmptyList, blockContext).toCovariantSet
      for {
        indicesResult <- processIndices(blockContext.requestContext, resolvedAllowedIndices, blockContext.indices, kibanaIndexFrom(blockContext))
        aliasesResult <- processIndices(blockContext.requestContext, resolvedAllowedIndices, blockContext.aliases, kibanaIndexFrom(blockContext))
      } yield {
        (indicesResult, aliasesResult) match {
          case (ProcessResult.Ok(indices), ProcessResult.Ok(aliases)) =>
            Permitted(blockContext.withIndices(indices).withAliases(aliases))
          case (ProcessResult.Failed.IndexNotFound, _) =>
            Denied(Cause.IndexNotFound(
              getAllowedClusterNames(blockContext.requestContext, resolvedAllowedIndices)
            ))
          case (ProcessResult.Failed.Other, _) =>
            reject()
          case (_, ProcessResult.Failed.IndexNotFound) =>
            Denied(Cause.AliasNotFound)
          case (_, ProcessResult.Failed.Other) =>
            reject()
        }
      }
    }
  }

  private def processSnapshotRequest(blockContext: SnapshotRequestBlockContext): Task[Decision[SnapshotRequestBlockContext]] = {
    if (matchAll) Task.now(Permitted(blockContext))
    else if (blockContext.filteredIndices.isEmpty) processRequestWithoutIndices(blockContext)
    else processIndicesRequest(blockContext)
  }

  private def processDataStreamRequest(blockContext: DataStreamRequestBlockContext): Task[Decision[DataStreamRequestBlockContext]] = {
    if (matchAll) Task.now(Permitted(blockContext))
    else if (blockContext.backingIndices == BackingIndices.IndicesNotInvolved) processRequestWithoutIndices(blockContext)
    else processIndicesRequest(blockContext)
  }

  private def getAllowedClusterNames(requestContext: RequestContext,
                                     allAllowedIndices: Set[ClusterIndexName]): Set[ClusterName.Full] = {
    given RequestId = requestContext.id.toRequestId
    def isLocalClusterAllowed: Boolean = allAllowedIndices.exists {
      case ClusterIndexName.Local(_) => true
      case ClusterIndexName.Remote(_, _) => false
    }

    val clusterNamesFromIndices = allAllowedIndices.flatMap {
      case ClusterIndexName.Local(_) => None
      case ClusterIndexName.Remote(_, cluster) => Some(cluster)
    }
    val matcher = PatternsMatcher.create(clusterNamesFromIndices)
    val allowedRemoteClusters = matcher.filter(requestContext.esServices.clusterService.allRemoteClusterNames)
    allowedRemoteClusters ++ Option.when(isLocalClusterAllowed)(ClusterName.Full.local).toCovariantSet
  }

  private def kibanaIndexFrom(blockContext: BlockContext) = {
    blockContext.blockMetadata.kibanaPolicy.flatMap(_.index)
  }

  private val matchAll = settings.allowedIndices.exists {
    case AlreadyResolved(indices) if indices.exists(_.allIndicesRequested) => true
    case _ => false
  }

  private def reject[T]() = Decision.Denied[T](Cause.NotAuthorized)
}

object IndicesRule {

  implicit case object Name extends RuleName[IndicesRule] {
    override val name = Rule.Name("indices")
  }

  final case class Settings(allowedIndices: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]],
                            mustInvolveIndices: Boolean)

  private[indices] sealed trait ProcessResult
  private[indices] object ProcessResult {
    final case class Ok(indices: Set[RequestedIndex[ClusterIndexName]]) extends ProcessResult
    sealed trait Failed extends ProcessResult
    object Failed {
      case object IndexNotFound extends Failed
      case object Other extends Failed
    }
  }

}