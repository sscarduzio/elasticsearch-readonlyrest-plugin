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
package tech.beshu.ror.es.request.context.types

import cats.implicits._
import cats.data.NonEmptyList
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.index.reindex.ReindexRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.{CannotModify, Modified, ShouldBeInterrupted}
import tech.beshu.ror.utils.LoggerOps._
import tech.beshu.ror.utils.ReflecUtils.invokeMethodCached
import tech.beshu.ror.utils.ScalaOps._

import scala.util.{Failure, Success, Try}

class ReindexEsRequestContext(actionRequest: ReindexRequest,
                              esContext: EsContext,
                              clusterService: RorClusterService,
                              override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[ReindexRequest](actionRequest, esContext, clusterService, threadPool) {

  override protected def indicesFrom(request: ReindexRequest): Set[IndexName] = {
    Try {
      val sr = invokeMethodCached(request, request.getClass, "getSearchRequest").asInstanceOf[SearchRequest]
      val ir = invokeMethodCached(request, request.getClass, "getDestination").asInstanceOf[IndexRequest]
      sr.indices.asSafeSet ++ Set(ir.index())
    } fold(
      ex => {
        logger.errorEx(s"Cannot extract indices from ReindexRequest", ex)
        Set.empty[String]
      },
      identity
    ) flatMap {
      IndexName.fromString
    }
  }

  override protected def update(request: ReindexRequest, indices: NonEmptyList[IndexName]): ModificationResult = {
    modifyIndicesOf(actionRequest, indices) match {
      case Success(modificationResult) =>
        modificationResult
      case Failure(ex) =>
        logger.error(s"[${id.show}] Cannot modify ReindexRequest", ex)
        CannotModify
    }
  }

  private def modifyIndicesOf(actionRequest: ReindexRequest, indices: NonEmptyList[IndexName]) = {
    Try {
      val sr = invokeMethodCached(actionRequest, actionRequest.getClass, "getSearchRequest").asInstanceOf[SearchRequest]
      val expandedIndicesOfSearchRequest = clusterService.expandIndices(sr.indices().asSafeSet.flatMap(IndexName.fromString))
      val remainingSearchIndices = expandedIndicesOfSearchRequest.intersect(indices.toList.toSet).toList
      sr.indices(remainingSearchIndices.map(_.value.value): _*)

      val ir = invokeMethodCached(actionRequest, actionRequest.getClass, "getDestination").asInstanceOf[IndexRequest]
      IndexName.fromString(ir.index()) match {
        case Some(destinationIndex) if indices.toList.contains(destinationIndex) =>
          Modified
        case Some(_) | None =>
          logger.info(s"[${id.show}] Destination index of _reindex request is forbidden")
          ShouldBeInterrupted
      }
    }
  }
}
