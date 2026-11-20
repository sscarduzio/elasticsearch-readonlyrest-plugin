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
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.*
import tech.beshu.ror.accesscontrol.blocks.BlockContext.*
import tech.beshu.ror.accesscontrol.blocks.BlockContext.DataStreamRequestBlockContext.BackingIndices
import tech.beshu.ror.accesscontrol.blocks.BlockContext.HasIndexPacks.{
  indexPacksFromFilterableMultiBlockContext,
  indexPacksFromMultiIndexBlockContext
}
import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiIndexRequestBlockContext.Indices
import tech.beshu.ror.accesscontrol.blocks.BlockContextUpdater.*
import tech.beshu.ror.accesscontrol.blocks.BlockContextWithIndexPacksUpdater.{
  FilterableMultiRequestBlockContextWithIndexPacksUpdater,
  MultiIndexRequestBlockContextWithIndexPacksUpdater
}
import tech.beshu.ror.accesscontrol.blocks.BlockContextWithIndicesUpdater.{
  FilterableRequestBlockContextWithIndicesUpdater,
  GeneralIndexRequestBlockContextWithIndicesUpdater
}
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause
import tech.beshu.ror.accesscontrol.blocks.Decision.{Denied, Permitted}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleName}
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.IndicesRule.*
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.clusterindices.{
  AllClusterIndices,
  LocalIndicesManager
}
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.templates.AllTemplateIndices
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote.ClusterName
import tech.beshu.ror.accesscontrol.domain.DataStreamName.OrWildcardWhenEmpty
import tech.beshu.ror.accesscontrol.domain.KibanaIndexName.*
import tech.beshu.ror.accesscontrol.domain.{
  ClusterIndexName,
  DataStreamName,
  KibanaIndexName,
  RequestId,
  RequestedIndex
}
import tech.beshu.ror.accesscontrol.matchers.{PatternsMatcher, UniqueIdentifierGenerator}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.{resolveAll, resolveAllIfPreResolved}
import tech.beshu.ror.syntax.*

class IndicesRule(override val settings: Settings, override val identifierGenerator: UniqueIdentifierGenerator)
    extends RegularRule
    with AllClusterIndices
    with AllTemplateIndices {

  import IndicesRule.*

  override val name: Rule.Name = IndicesRule.Name.name

  // Optimization: when the allowed indices are pre-resolved, split them and build the matchers once
  // instead of per request.
  private val staticAllowedIndices: Option[AllowedClusterIndices] =
    resolveAllIfPreResolved(settings.allowedIndices.toNonEmptyList)
      .map(indices => new AllowedClusterIndices(indices.toList.toCovariantSet))

  override def regularCheck[B <: BlockContext: BlockContextUpdater](blockContext: B): Task[Decision[B]] = {
    BlockContextUpdater[B] match {
      case UserMetadataRequestBlockContextUpdater    => processRequestWithoutIndices(blockContext)
      case GeneralNonIndexRequestBlockContextUpdater => processRequestWithoutIndices(blockContext)
      case RepositoryRequestBlockContextUpdater      => processRequestWithoutIndices(blockContext)
      case SnapshotRequestBlockContextUpdater        => processSnapshotRequest(blockContext)
      case DataStreamRequestBlockContextUpdater      => processDataStreamRequest(blockContext)
      case GeneralIndexRequestBlockContextUpdater    => processIndicesRequest(blockContext)
      case FilterableRequestBlockContextUpdater      => processIndicesRequest(blockContext)
      case MultiIndexRequestBlockContextUpdater      => processIndicesPacks(blockContext)
      case FilterableMultiRequestBlockContextUpdater => processIndicesPacks(blockContext)
      case AliasRequestBlockContextUpdater           => processAliasRequest(blockContext)
      case TemplateRequestBlockContextUpdater        => processTemplateRequest(blockContext)
      case RorApiRequestBlockContextUpdater          => processRequestWithoutIndices(blockContext)
    }
  }

  private def processRequestWithoutIndices[B <: BlockContext](blockContext: B): Task[Decision[B]] = Task.now {
    if (settings.mustInvolveIndices) reject()
    else Permitted(blockContext)
  }

  private def processIndicesRequest[B <: BlockContext: BlockContextWithIndicesUpdater](
      blockContext: B
  ): Task[Decision[B]] =
    processIndicesRequestWith(blockContext, kibanaIndexFrom(blockContext))

  private def processIndicesRequestWith[B <: BlockContext: BlockContextWithIndicesUpdater](
      blockContext: B,
      kibanaIndex: Option[KibanaIndexName]
  ): Task[Decision[B]] = {
    if (matchAll) {
      Task.now(Permitted(blockContext))
    } else {
      val allowedIndices = allowedIndicesFor(blockContext)
      processIndices(blockContext.requestContext, allowedIndices, blockContext.indices, kibanaIndex)
        .map {
          case ProcessResult.Ok(filteredIndices) =>
            val allowedClusters = getAllowedClusterNames(blockContext.requestContext, allowedIndices)
            Permitted(blockContext.withIndices(filteredIndices, allowedIndices.all).withClusters(allowedClusters))
          case ProcessResult.Failed.IndexNotFound =>
            val allowedClusters = getAllowedClusterNames(blockContext.requestContext, allowedIndices)
            Denied(Cause.IndexNotFound(allowedClusters))
          case ProcessResult.Failed.ClusterUnavailable(cluster) =>
            val allowedClusters = getAllowedClusterNames(blockContext.requestContext, allowedIndices)
            Permitted(
              blockContext.withIndices(nonexistentIndexOn(cluster), allowedIndices.all).withClusters(allowedClusters)
            )
          case ProcessResult.Failed.Other =>
            reject()
        }
    }
  }

  private def processIndicesPacks[B <: BlockContext: BlockContextWithIndexPacksUpdater: HasIndexPacks](
      blockContext: B
  ): Task[Decision[B]] = {
    if (matchAll) {
      Task.now(Permitted(blockContext))
    } else {
      import tech.beshu.ror.accesscontrol.blocks.BlockContext.HasIndexPacks.*
      def atLeastOneFound(indices: List[Indices]) = indices.exists(_.isInstanceOf[Indices.Found])

      val allowedIndices = allowedIndicesFor(blockContext)
      blockContext.indexPacks
        .foldLeft(Task.now(List.empty[Indices].asRight[Unit])) { case (acc, pack) =>
          acc.flatMap {
            case Right(currentList) =>
              pack match {
                case Indices.Found(indices) =>
                  processIndices(
                    blockContext.requestContext,
                    allowedIndices,
                    indices,
                    kibanaIndexFrom(blockContext)
                  ) map {
                    case ProcessResult.Ok(narrowedIndices)  => Right(Indices.Found(narrowedIndices) :: currentList)
                    case ProcessResult.Failed.IndexNotFound => Right(Indices.NotFound :: currentList)
                    case ProcessResult.Failed.ClusterUnavailable(cluster) =>
                      Right(Indices.Found(nonexistentIndexOn(cluster)) :: currentList)
                    case ProcessResult.Failed.Other => Left(())
                  }
                case Indices.NotFound =>
                  Task.now(Right(Indices.NotFound :: currentList))
              }
            case result @ Left(_) =>
              Task.now(result)
          }
        }
        .map {
          case Right(indices) if atLeastOneFound(indices) => Permitted(blockContext.withIndicesPacks(indices.reverse))
          case Right(_)                                   =>
            Denied(
              Cause.IndexNotFound(
                getAllowedClusterNames(blockContext.requestContext, allowedIndices)
              )
            )
          case Left(_) => reject()
        }
    }
  }

  // ES fails the search that names an unavailable cluster with skip_unavailable: false. In a multi-request, ES fails only
  // that search. A nonexistent index on the cluster makes ES give the same result. If the cluster is available again
  // when ES runs the search, the search gets no data, because the index does not exist.
  private def nonexistentIndexOn(cluster: ClusterName.Full): Set[RequestedIndex[ClusterIndexName]] =
    Set(RequestedIndex(ClusterIndexName.Remote.randomNonexistentIndex(cluster), excluded = false))

  private def processAliasRequest(blockContext: AliasRequestBlockContext): Task[Decision[AliasRequestBlockContext]] = {
    if (matchAll) {
      Task.now(Permitted(blockContext))
    } else {
      val allowedIndices = allowedIndicesFor(blockContext)
      for {
        indicesResult <- processIndices(
          blockContext.requestContext,
          allowedIndices,
          blockContext.indices,
          kibanaIndexFrom(blockContext)
        )
        aliasesResult <- processIndices(
          blockContext.requestContext,
          allowedIndices,
          blockContext.aliases,
          kibanaIndexFrom(blockContext)
        )
      } yield {
        // ES rejects each alias request that names a remote index, so a nonexistent index on the unavailable cluster
        // gives that ES error. It replaces also the other part, so that ES gets no index that the rule did not allow.
        def clusterUnavailable(cluster: ClusterName.Full) =
          Permitted(blockContext.withIndices(nonexistentIndexOn(cluster)).withAliases(nonexistentIndexOn(cluster)))

        (indicesResult, aliasesResult) match {
          case (ProcessResult.Ok(indices), ProcessResult.Ok(aliases)) =>
            Permitted(blockContext.withIndices(indices).withAliases(aliases))
          case (ProcessResult.Failed.ClusterUnavailable(cluster), _) =>
            clusterUnavailable(cluster)
          case (_, ProcessResult.Failed.ClusterUnavailable(cluster)) =>
            clusterUnavailable(cluster)
          case (ProcessResult.Failed.IndexNotFound, _) =>
            Denied(
              Cause.IndexNotFound(
                getAllowedClusterNames(blockContext.requestContext, allowedIndices)
              )
            )
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

  private def processSnapshotRequest(
      blockContext: SnapshotRequestBlockContext
  ): Task[Decision[SnapshotRequestBlockContext]] = {
    if (matchAll) Task.now(Permitted(blockContext))
    else if (blockContext.filteredIndices.isEmpty) processRequestWithoutIndices(blockContext)
    else processIndicesRequest(blockContext)
  }

  private def processDataStreamRequest(
      blockContext: DataStreamRequestBlockContext
  ): Task[Decision[DataStreamRequestBlockContext]] = {
    if (matchAll) {
      Task.now(Permitted(blockContext))
    } else if (blockContext.requestContext.isReadOnlyRequest) {
      blockContext.backingIndices match {
        case BackingIndices.IndicesNotInvolved =>
          processDataStreamReadRequest(blockContext)
        case _: BackingIndices.IndicesInvolved =>
          processIndicesRequest(blockContext).flatMap(whenPermitted(narrowDataStreams))
      }
    } else {
      blockContext.backingIndices match {
        case BackingIndices.IndicesNotInvolved =>
          processDataStreamWriteRequest(blockContext)
        case _: BackingIndices.IndicesInvolved =>
          processIndicesRequestWith(blockContext, kibanaIndex = None)
            .flatMap(whenPermitted(processDataStreamsOfWriteRequestWithIndices))
      }
    }
  }

  private def whenPermitted(
      next: DataStreamRequestBlockContext => Task[Decision[DataStreamRequestBlockContext]]
  )(decision: Decision[DataStreamRequestBlockContext]): Task[Decision[DataStreamRequestBlockContext]] =
    decision match {
      case Permitted(blockContext) => next(blockContext)
      case denied                  => Task.now(denied)
    }

  // A data stream create, delete or promote passes only when the rule allows each data stream name. The rule checks a
  // bulk write to the data stream the same way. No names means all data streams.
  private def processDataStreamWriteRequest(
      blockContext: DataStreamRequestBlockContext
  ): Task[Decision[DataStreamRequestBlockContext]] = {
    val allowedIndices = allowedIndicesFor(blockContext)
    areDataStreamWritesAllowed(
      blockContext,
      allowedIndices,
      blockContext.dataStreams.orWildcardWhenEmpty.map(asLocalIndexName)
    ).flatMap {
      case true  => resolveDataStreamPatternsOfWriteRequest(blockContext, allowedIndices)
      case false => Task.now(reject())
    }
  }

  // A data stream write gets no Kibana exception. The rule must allow the data stream name.
  private def areDataStreamWritesAllowed(
      blockContext: DataStreamRequestBlockContext,
      allowedIndices: AllowedClusterIndices,
      dataStreams: Set[ClusterIndexName.Local]
  ): Task[Boolean] = {
    if (dataStreams.isEmpty) {
      Task.now(true)
    } else {
      processIndices(
        blockContext.requestContext,
        allowedIndices,
        dataStreams.map(dataStream => RequestedIndex[ClusterIndexName](dataStream, excluded = false)),
        determinedKibanaIndex = None
      ).map {
        case ProcessResult.Ok(_)     => true
        case _: ProcessResult.Failed => false
      }
    }
  }

  // Each data stream that a requested pattern matches must also be allowed. The request then gets the names of these data
  // streams instead of the pattern. When the pattern matches no data stream, the request gets a name that matches nothing.
  private def resolveDataStreamPatternsOfWriteRequest(
      blockContext: DataStreamRequestBlockContext,
      allowedIndices: AllowedClusterIndices
  ): Task[Decision[DataStreamRequestBlockContext]] = {
    given RequestId = blockContext.requestContext.id.toRequestId
    val (fullNames, patterns) = blockContext.dataStreams.orWildcardWhenEmpty.partition {
      case _: DataStreamName.Full                                                   => true
      case DataStreamName.Pattern(_) | DataStreamName.All | DataStreamName.Wildcard => false
    }
    if (patterns.isEmpty) {
      Task.now(Permitted(blockContext))
    } else {
      val patternsMatcher = PatternsMatcher.create(patterns.map(asLocalIndexName))
      for {
        allDataStreams <- localIndicesManager(blockContext, allowedIndices).allDataStreams
        matchedDataStreams = allDataStreams.filter(patternsMatcher.`match`)
        allowed <- areDataStreamWritesAllowed(blockContext, allowedIndices, matchedDataStreams)
      } yield {
        val dataStreams =
          fullNames ++ matchedDataStreams.flatMap(dataStream => DataStreamName.fromString(dataStream.stringify))
        if (!allowed) reject()
        else if (dataStreams.nonEmpty) Permitted(blockContext.withDataStreams(dataStreams))
        else Permitted(blockContext.withDataStreams(Set(nonexistentDataStreamPattern)))
      }
    }
  }

  // A data stream read request without backing indices (stats) involves no indices, so `must_involve_indices` rejects it
  private def processDataStreamReadRequest(
      blockContext: DataStreamRequestBlockContext
  ): Task[Decision[DataStreamRequestBlockContext]] = {
    if (settings.mustInvolveIndices) Task.now(reject())
    else narrowDataStreams(blockContext)
  }

  // A data stream read request gets only the requested data streams that the rule allows. When the rule allows none of
  // them, the request gets a name that matches nothing, because ES reads no names as all data streams.
  private def narrowDataStreams(
      blockContext: DataStreamRequestBlockContext
  ): Task[Decision[DataStreamRequestBlockContext]] = {
    given RequestId = blockContext.requestContext.id.toRequestId
    val allowedIndices = allowedIndicesFor(blockContext)
    val requestedMatcher = PatternsMatcher.create(blockContext.dataStreams.orWildcardWhenEmpty.map(asLocalIndexName))
    val indicesManager = localIndicesManager(blockContext, allowedIndices)
    for {
      allDataStreams <- indicesManager.allDataStreams
      backingIndicesPerDataStream <- indicesManager.backingIndicesPerDataStreamMap
    } yield {
      val allowedDataStreams =
        allDataStreams
          .filter(requestedMatcher.`match`)
          .filter(isDataStreamAllowed(_, allowedIndices, backingIndicesPerDataStream, kibanaIndexFrom(blockContext)))
          .flatMap(dataStream => DataStreamName.fromString(dataStream.stringify))
      if (allowedDataStreams.nonEmpty) Permitted(blockContext.withDataStreams(allowedDataStreams))
      else Permitted(blockContext.withDataStreams(Set(nonexistentDataStreamPattern)))
    }
  }

  // A _modify or migrate request passes only when the rule allows each data stream name, with no pattern. So an allowed
  // index cannot join or leave another data stream. A data stream write gets no Kibana exception.
  private def processDataStreamsOfWriteRequestWithIndices(
      blockContext: DataStreamRequestBlockContext
  ): Task[Decision[DataStreamRequestBlockContext]] = {
    given RequestId = blockContext.requestContext.id.toRequestId
    val allowedIndices = allowedIndicesFor(blockContext)
    localIndicesManager(blockContext, allowedIndices).backingIndicesPerDataStreamMap.map {
      backingIndicesPerDataStream =>
        // Migrate has no names: its data stream gets the name of the alias, which the rule checked as an index. ES rejects
        // `_all` in _modify, because no data stream has that name.
        val requestHasNoDataStreamNames = blockContext.dataStreams == Set(DataStreamName.all)
        val allDataStreamsAllowed = requestHasNoDataStreamNames || blockContext.dataStreams.forall {
          case DataStreamName.Full(name) =>
            isDataStreamAllowed(
              ClusterIndexName.Local.fromNes(name),
              allowedIndices,
              backingIndicesPerDataStream,
              kibanaIndex = None
            )
          case DataStreamName.Pattern(_) | DataStreamName.All | DataStreamName.Wildcard =>
            false
        }
        if (allDataStreamsAllowed) Permitted(blockContext)
        else reject()
    }
  }

  // A data stream is allowed when the rule allows its name or all its backing indices, or when it relates to the given
  // Kibana index.
  private def isDataStreamAllowed(
      dataStream: ClusterIndexName.Local,
      allowedIndices: AllowedClusterIndices,
      backingIndicesPerDataStream: Map[ClusterIndexName.Local, Set[ClusterIndexName.Local]],
      kibanaIndex: Option[KibanaIndexName]
  ): Boolean = {
    allowedIndices.localMatcher.`match`(dataStream) ||
    backingIndicesPerDataStream.get(dataStream).exists { backingIndices =>
      backingIndices.nonEmpty && backingIndices.forall(allowedIndices.localMatcher.`match`)
    } ||
    kibanaIndex.exists(dataStream.isRelatedToKibanaIndex)
  }

  private def localIndicesManager(blockContext: BlockContext, allowedIndices: AllowedClusterIndices) =
    new LocalIndicesManager(blockContext.requestContext, allowedIndices.localMatcher)

  private def allowedIndicesFor(blockContext: BlockContext): AllowedClusterIndices =
    staticAllowedIndices.getOrElse {
      new AllowedClusterIndices(resolveAll(settings.allowedIndices.toNonEmptyList, blockContext).toCovariantSet)
    }

  private def getAllowedClusterNames(
      requestContext: RequestContext,
      allowedIndices: AllowedClusterIndices
  ): Set[ClusterName.Full] = {
    given RequestId = requestContext.id.toRequestId
    val allowedRemoteClusters =
      allowedIndices.remoteClusterNamesMatcher.filter(requestContext.esServices.clusterService.allRemoteClusterNames)
    allowedRemoteClusters ++ Option.when(allowedIndices.isLocalClusterAllowed)(ClusterName.Full.local).toCovariantSet
  }

  private def kibanaIndexFrom(blockContext: BlockContext) = {
    blockContext.blockMetadata.kibanaPolicy.map(_.index)
  }

  private val matchAll = settings.allowedIndices.exists {
    case AlreadyResolved(indices) if indices.exists(_ == ClusterIndexName.Local.wildcard) => true
    case _                                                                                => false
  }

  private def reject[T]() = Decision.Denied[T](Cause.NotAuthorized)
}

object IndicesRule {

  implicit case object Name extends RuleName[IndicesRule] {
    override val name = Rule.Name("indices")
  }

  final case class Settings(
      allowedIndices: NonEmptySet[RuntimeMultiResolvableVariable[ClusterIndexName]],
      mustInvolveIndices: Boolean
  )

  // Allowed indices pre-split into local/remote with lazily compiled matchers: built once at rule
  // construction for a static config, or per request when runtime variables are involved.
  private[indices] final class AllowedClusterIndices(val all: Set[ClusterIndexName]) {

    val (remote, local) = {
      val remoteIndices = Set.newBuilder[ClusterIndexName.Remote]
      val localIndices = Set.newBuilder[ClusterIndexName.Local]
      all.foreach {
        case localIndex: ClusterIndexName.Local   => localIndices += localIndex
        case remoteIndex: ClusterIndexName.Remote => remoteIndices += remoteIndex
      }
      (remoteIndices.result(), localIndices.result())
    }

    lazy val localMatcher: PatternsMatcher[ClusterIndexName.Local] = PatternsMatcher.create(local)
    lazy val remoteMatcher: PatternsMatcher[ClusterIndexName.Remote] = PatternsMatcher.create(remote)
    lazy val remoteClusterNamesMatcher: PatternsMatcher[ClusterName] = PatternsMatcher.create(remote.map(_.cluster))

    def isLocalClusterAllowed: Boolean = local.nonEmpty
  }

  private def asLocalIndexName(dataStream: DataStreamName): ClusterIndexName.Local = dataStream match {
    case DataStreamName.Full(name)                    => ClusterIndexName.Local.fromNes(name)
    case DataStreamName.Pattern(namePattern)          => ClusterIndexName.Local.fromNes(namePattern)
    case DataStreamName.All | DataStreamName.Wildcard => ClusterIndexName.Local.wildcard
  }

  private def nonexistentDataStreamPattern: DataStreamName =
    DataStreamName.Pattern.fromNes(
      NonEmptyString.unsafeFrom(s"${ClusterIndexName.Local.randomNonexistentIndex().stringify}*")
    )

  private[indices] sealed trait ProcessResult

  private[indices] object ProcessResult {
    final case class Ok(indices: Set[RequestedIndex[ClusterIndexName]]) extends ProcessResult
    sealed trait Failed extends ProcessResult

    object Failed {
      case object IndexNotFound extends Failed
      final case class ClusterUnavailable(cluster: ClusterName.Full) extends Failed
      case object Other extends Failed
    }

  }

}
