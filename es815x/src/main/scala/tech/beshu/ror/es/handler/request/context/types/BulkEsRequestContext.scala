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
import cats.implicits.*
import org.elasticsearch.action.DocWriteRequest
import org.elasticsearch.action.bulk.BulkRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.{MultiIndexRequestBlockContext, RequestedIndex}
import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiIndexRequestBlockContext.Indices
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult.{Modified, ShouldBeInterrupted}
import tech.beshu.ror.es.handler.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*

import scala.jdk.CollectionConverters.*

class BulkEsRequestContext(actionRequest: BulkRequest,
                           esContext: EsContext,
                           clusterService: RorClusterService,
                           override val threadPool: ThreadPool)
  extends BaseEsRequestContext[MultiIndexRequestBlockContext](esContext, clusterService)
    with EsRequest[MultiIndexRequestBlockContext] {

  override lazy val initialBlockContext: MultiIndexRequestBlockContext = MultiIndexRequestBlockContext(
    requestContext = this,
    userMetadata = UserMetadata.from(this),
    responseHeaders = Set.empty,
    responseTransformations = List.empty,
    indexPacks = indexPacksFrom(actionRequest)
  )

  override protected def modifyRequest(blockContext: MultiIndexRequestBlockContext): ModificationResult = {
    val modifiedPacksOfIndices = blockContext.indexPacks
    val requests = actionRequest.requests().asScala.toList
    if (requests.size == modifiedPacksOfIndices.size) {
      requests
        .zip(modifiedPacksOfIndices)
        .foldLeft(Modified: ModificationResult) {
          case (Modified, (request, pack)) => updateRequest(request, pack)
          case (_, _) => ShouldBeInterrupted
        }
    } else {
      logger.error(s"[${id.show}] Cannot alter MultiGetRequest request, because origin request contained different " +
        s"number of requests, than altered one. This can be security issue. So, it's better for forbid the request")
      ShouldBeInterrupted
    }
  }

  private def indexPacksFrom(request: BulkRequest): List[Indices] = {
    request
      .requests().asScala
      .map { r => Indices.Found(indicesFrom(r)) }
      .toList
  }

  private def indicesFrom(request: DocWriteRequest[_]): Set[RequestedIndex] = {
    request
      .indices.asSafeSet
      .flatMap(RequestedIndex.fromString)
      .orWildcardWhenEmpty
  }

  private def updateRequest(request: DocWriteRequest[_], indexPack: Indices): ModificationResult = {
    indexPack match {
      case Indices.Found(indices) =>
        NonEmptyList.fromList(indices.toList) match {
          case Some(nel) =>
            updateRequestWithIndices(request, nel)
            Modified
          case None =>
            logger.error(s"[${id.show}] Cannot alter MultiGetRequest request, because empty list of indices was found")
            ShouldBeInterrupted
        }
      case Indices.NotFound =>
        logger.error(s"[${id.show}] Cannot alter MultiGetRequest request, because no allowed indices were found")
        ShouldBeInterrupted
    }
  }

  private def updateRequestWithIndices(request: DocWriteRequest[_], indices: NonEmptyList[RequestedIndex]) = {
    if (indices.tail.nonEmpty) {
      logger.warn(s"[${id.show}] Filtered result contains more than one index. First was taken. The whole set of indices [${indices.show}]")
    }
    request.index(indices.head.stringify)
  }

}