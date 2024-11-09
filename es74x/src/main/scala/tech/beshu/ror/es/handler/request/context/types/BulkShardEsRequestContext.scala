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
import org.elasticsearch.action.bulk.BulkShardRequest
import org.elasticsearch.index.Index
import org.elasticsearch.threadpool.ThreadPool
import org.reflections.ReflectionUtils
import tech.beshu.ror.accesscontrol.AccessControlList.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.ModificationResult.{CannotModify, Modified}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*

import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

class BulkShardEsRequestContext(actionRequest: BulkShardRequest,
                                esContext: EsContext,
                                aclContext: AccessControlStaticContext,
                                clusterService: RorClusterService,
                                override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[BulkShardRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def indicesFrom(request: BulkShardRequest): Set[ClusterIndexName] = {
    request.indices().asSafeSet.flatMap(ClusterIndexName.fromString)
  }

  override protected def update(request: BulkShardRequest,
                                filteredIndices: NonEmptyList[RequestedIndex[ClusterIndexName]],
                                allAllowedIndices: NonEmptyList[ClusterIndexName]): ModificationResult = {
    tryUpdate(request, filteredIndices) match {
      case Success(_) =>
        Modified
      case Failure(ex) =>
        logger.error(s"[${id.show}] Cannot modify BulkShardRequest", ex)
        CannotModify
    }
  }

  private def tryUpdate(request: BulkShardRequest, indices: NonEmptyList[ClusterIndexName]) = {
    if (indices.tail.nonEmpty) {
      logger.warn(s"[${id.show}] Filtered result contains more than one index. First was taken. The whole set of indices [${indices.show}]")
    }
    val singleIndex = indices.head
    val uuid =  clusterService.indexOrAliasUuids(singleIndex).toList.head
    ReflectionUtils
      .getAllFields(request.shardId().getClass, ReflectionUtils.withName("index")).asScala
      .foldLeft(Try(())) {
        case (Success(_), field) =>
          field.setAccessible(true)
          Try(field.set(request.shardId(), new Index(singleIndex.stringify, uuid)))
        case (left, _) =>
          left
      }
  }

}
