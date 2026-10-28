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
package tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.clusterindices

import cats.kernel.Semigroup
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.IndicesRule
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.IndicesRule.{
  AllowedClusterIndices,
  ProcessResult
}
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.domain.CanPass
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.domain.CanPass.No.Reason
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote.ClusterName
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, KibanaIndexName, RequestId, RequestedIndex}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.es.services.EsClusterService
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

trait AllClusterIndices extends BaseIndicesProcessor {
  this: IndicesRule =>

  protected def processIndices(
      requestContext: RequestContext,
      allowedIndices: AllowedClusterIndices,
      requestedIndices: Set[RequestedIndex[ClusterIndexName]],
      determinedKibanaIndex: Option[KibanaIndexName]
  ): Task[ProcessResult] = {
    val (requestedRemoteIndices, requestedLocalIndices) = splitIntoRequestedRemoteAndLocalIndices(requestedIndices)

    (UniqueNonEmptyList.from(requestedLocalIndices), UniqueNonEmptyList.from(requestedRemoteIndices)) match {
      case (Some(nonEmptyRequestedLocalIndices), Some(nonEmptyRequestedRemoteIndices)) =>
        import AllClusterIndices.*
        for {
          localResult <- processLocalIndices(
            requestContext,
            allowedIndices,
            nonEmptyRequestedLocalIndices,
            determinedKibanaIndex
          )
          remoteResult <- processRemoteIndices(
            requestContext,
            allowedIndices,
            nonEmptyRequestedRemoteIndices,
            determinedKibanaIndex
          )
        } yield Semigroup.combine(localResult, remoteResult)
      case (Some(nonEmptyRequestedLocalIndices), None) =>
        processLocalIndices(requestContext, allowedIndices, nonEmptyRequestedLocalIndices, determinedKibanaIndex)
      case (None, Some(nonEmptyRequestedRemoteIndices)) =>
        processRemoteIndices(requestContext, allowedIndices, nonEmptyRequestedRemoteIndices, determinedKibanaIndex)
      case (None, None) =>
        val clusterService = requestContext.esServices.clusterService
        given RequestId = requestContext.id.toRequestId
        if (clusterService.localIndicesSnapshot.raw.nonEmpty || clusterService.localDataStreamsSnapshot.raw.nonEmpty) {
          Task.now(
            ProcessResult.Ok(
              allowedIndices.all.map(RequestedIndex(_, excluded = false))
            )
          )
        } else {
          Task.now(ProcessResult.Failed.IndexNotFound)
        }
    }
  }

  private def processLocalIndices(
      requestContext: RequestContext,
      allowedIndices: AllowedClusterIndices,
      requestedIndices: UniqueNonEmptyList[RequestedIndex[ClusterIndexName.Local]],
      determinedKibanaIndex: Option[KibanaIndexName]
  ): Task[ProcessResult] = {
    implicit val allowedIndicesManager: LocalIndicesManager = new LocalIndicesManager(
      requestContext,
      allowedIndices.localMatcher
    )
    implicit val requestContextImpl: RequestContext = requestContext
    logger.debug(
      s"Checking local indices (allowed: [${allowedIndices.local.show}], requested: [${requestedIndices.show}])"
    )
    canPass[ClusterIndexName.Local](requestContext, determinedKibanaIndex, requestedIndices)
      .map {
        case CanPass.Yes(narrowedIndices) =>
          ProcessResult.Ok(narrowedIndices)
        case CanPass.No(Some(Reason.IndexNotExist)) =>
          ProcessResult.Failed.IndexNotFound
        case CanPass.No(_) =>
          ProcessResult.Failed.Other
      }
  }

  private def processRemoteIndices(
      requestContext: RequestContext,
      allowedIndices: AllowedClusterIndices,
      requestedIndices: UniqueNonEmptyList[RequestedIndex[ClusterIndexName.Remote]],
      determinedKibanaIndex: Option[KibanaIndexName]
  ): Task[ProcessResult] = {
    given RequestId = requestContext.id.toRequestId
    RemoteIndicesManager
      .create(
        requestContext,
        allowedIndices.remoteMatcher,
        requestedRemoteClusters(requestContext.esServices.clusterService, allowedIndices, requestedIndices)
      )
      .flatMap {
        case Left(error) =>
          Task.now(ProcessResult.Failed.ClusterUnavailable(error.cluster))
        case Right(manager) =>
          implicit val indicesManager: RemoteIndicesManager = manager
          logger.debug(
            s"Checking remote indices (allowed: [${allowedIndices.remote.show}], requested: [${requestedIndices.show}])"
          )
          canPass(requestContext, determinedKibanaIndex, requestedIndices)
            .map {
              case CanPass.Yes(narrowedIndices) =>
                ProcessResult.Ok(narrowedIndices)
              case CanPass.No(Some(Reason.IndexNotExist)) =>
                ProcessResult.Failed.IndexNotFound
              case CanPass.No(_) =>
                ProcessResult.Failed.Other
            }
      }
  }

  private def requestedRemoteClusters(
      clusterService: EsClusterService,
      allowedIndices: AllowedClusterIndices,
      requestedIndices: UniqueNonEmptyList[RequestedIndex[ClusterIndexName.Remote]]
  )(
      implicit id: RequestId
  ): Set[ClusterName.Full] = {
    // An index must match an included pattern, so an excluded pattern does not need the indices of its cluster
    val included = requestedIndices.toList.filterNot(_.excluded).map(_.name.cluster)
    val (fullNames, patterns) = included.partitionMap {
      case full: ClusterName.Full       => Left(full)
      case pattern: ClusterName.Pattern => Right(pattern.esWildcardPattern)
    }
    val requestedClusters = clusterService.allRemoteClusterNames.filter { cluster =>
      fullNames.contains(cluster) || patterns.exists(_.matches(cluster.value.value))
    }
    // No index of a cluster that no allowed pattern names can be allowed. Thus ROR does not ask that cluster, and the
    // result of the rule does not depend on it.
    allowedIndices.remoteClusterNamesMatcher.filter(requestedClusters)
  }

  private def splitIntoRequestedRemoteAndLocalIndices(indices: Iterable[RequestedIndex[ClusterIndexName]]) = {
    val remoteIndices = Set.newBuilder[RequestedIndex[ClusterIndexName.Remote]]
    val localIndices = Set.newBuilder[RequestedIndex[ClusterIndexName.Local]]
    indices.foreach { requestedIndex =>
      requestedIndex.name match {
        case local: ClusterIndexName.Local   => localIndices += RequestedIndex(local, requestedIndex.excluded)
        case remote: ClusterIndexName.Remote => remoteIndices += RequestedIndex(remote, requestedIndex.excluded)
      }
    }
    (remoteIndices.result(), localIndices.result())
  }

}

object AllClusterIndices {

  implicit val processResultSemigroup: Semigroup[ProcessResult] = Semigroup.instance {
    case (unavailable: ProcessResult.Failed.ClusterUnavailable, _) => unavailable
    case (_, unavailable: ProcessResult.Failed.ClusterUnavailable) => unavailable
    case (ProcessResult.Ok(indices1), ProcessResult.Ok(indices2))  => ProcessResult.Ok(indices1 ++ indices2)
    case (ok @ ProcessResult.Ok(_), _: ProcessResult.Failed)       => ok
    case (_: ProcessResult.Failed, ok @ ProcessResult.Ok(_))       => ok
    case (failed: ProcessResult.Failed, _: ProcessResult.Failed)   => failed
  }

}
