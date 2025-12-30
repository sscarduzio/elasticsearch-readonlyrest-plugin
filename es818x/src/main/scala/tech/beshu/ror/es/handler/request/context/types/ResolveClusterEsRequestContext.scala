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
package tech.beshu.ror.es.handler.request.context.types

import cats.data.NonEmptyList
import org.elasticsearch.action.admin.indices.resolve.{ResolveClusterActionRequest, ResolveClusterActionResponse, ResolveClusterInfo}
import org.elasticsearch.threadpool.ThreadPool
import org.elasticsearch.transport.NoSuchRemoteClusterException
import org.joor.Reflect
import tech.beshu.ror.accesscontrol.AccessControlList.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote.ClusterName
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote.ClusterName.*
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestedIndex}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.ModificationResult.{Modified, UpdateResponse}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*

import scala.jdk.CollectionConverters.*

class ResolveClusterEsRequestContext(actionRequest: ResolveClusterActionRequest,
                                     esContext: EsContext,
                                     aclContext: AccessControlStaticContext,
                                     clusterService: RorClusterService,
                                     override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[ResolveClusterActionRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def requestedIndicesFrom(request: ResolveClusterActionRequest): Set[RequestedIndex[ClusterIndexName]] = {
    val indices = request
      .indices().asSafeSet
      .flatMap(RequestedIndex.fromString)
    if (indices.nonEmpty) {
      indices
    } else {
      (RequestedIndex.fromString("*") ++ RequestedIndex.fromString("*:*")).toCovariantSet
    }
  }

  override protected def update(request: ResolveClusterActionRequest,
                                filteredIndices: NonEmptyList[RequestedIndex[ClusterIndexName]],
                                allAllowedIndices: NonEmptyList[ClusterIndexName],
                                allowedClusters: Set[ClusterName.Full]): ModificationResult = {
    if (filteredIndices.toCovariantSet != requestedIndicesFrom(request)) {
      if (request.clusterInfoOnly()) {
        modifyClusterOnlyRequestAndResponse(request, allowedClusters)
      } else {
        request.indices(filteredIndices.stringify: _*)
        Modified
      }
    } else {
      Modified
    }
  }

  override def modifyWhenIndexNotFound(allowedClusters: Set[ClusterName.Full]): ModificationResult = {
    val requestedFullClusterName =
      initialBlockContext
        .indices.toList
        .flatMap[ClusterName] { r =>
          r.name match {
            case ClusterIndexName.Local(_) => Some(ClusterName.Full.local)
            case ClusterIndexName.Remote(_, clusterName@ClusterName.Full(_)) => Some(clusterName)
            case ClusterIndexName.Remote(_, clusterName@ClusterName.Pattern(_)) => None
          }
        }

    requestedFullClusterName.diff(allowedClusters.toList) match {
      case Nil =>
        val newRequestedNonexistentIndices = initialBlockContext
          .indices.toList
          .distinctBy(_.name.index match {
            case ClusterIndexName.Local(_) => ClusterName.Full.local
            case ClusterIndexName.Remote(_, cluster) => cluster
          })
          .map(_.randomNonexistentIndex())

        actionRequest.indices(newRequestedNonexistentIndices.stringify: _*)
        if (actionRequest.clusterInfoOnly()) {
          Reflect.on(actionRequest).set("clusterInfoOnly", false)
        }
        ModificationResult.Modified
      case head :: _ =>
        ModificationResult.CustomResponse.Failure(new NoSuchRemoteClusterException(head.stringify))
    }
  }

  private def modifyClusterOnlyRequestAndResponse(request: ResolveClusterActionRequest,
                                                  allowedClusters: Set[ClusterName.Full]) = {
    val allowedClustersWithWildcardIndex = allowedClusters.toList.map {
      case cluster if cluster == ClusterName.Full.local => "*"
      case cluster => s"${cluster.stringify}:*"
    }
    request.indices(allowedClustersWithWildcardIndex: _*)
    Reflect.on(actionRequest).set("clusterInfoOnly", false)
    UpdateResponse.sync {
      case response: ResolveClusterActionResponse =>
        new ResolveClusterActionResponse(
          response.getResolveClusterInfo.asScala
            .map { case (name, info) =>
              (name, new ResolveClusterInfo(info, info.getSkipUnavailable, true))
            }
            .asJava
        )
      case response => response
    }
  }
}
