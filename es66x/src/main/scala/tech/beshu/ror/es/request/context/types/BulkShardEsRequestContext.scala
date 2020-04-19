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

import cats.data.NonEmptyList
import cats.implicits._
import org.elasticsearch.action.bulk.BulkShardRequest
import org.elasticsearch.index.Index
import org.elasticsearch.threadpool.ThreadPool
import org.reflections.ReflectionUtils
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.{CannotModify, Modified}
import tech.beshu.ror.utils.ScalaOps._

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

class BulkShardEsRequestContext(actionRequest: BulkShardRequest,
                                esContext: EsContext,
                                aclContext: AccessControlStaticContext,
                                clusterService: RorClusterService,
                                override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[BulkShardRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def indicesFrom(request: BulkShardRequest): Set[IndexName] = {
    request.indices().asSafeSet.flatMap(IndexName.fromString)
  }

  override protected def update(request: BulkShardRequest, indices: NonEmptyList[IndexName]): ModificationResult = {
    tryUpdate(request, indices) match {
      case Success(_) =>
        Modified
      case Failure(ex) =>
        logger.error(s"[${id.show}] Cannot modify BulkShardRequest", ex)
        CannotModify
    }
  }

  private def tryUpdate(request: BulkShardRequest, indices: NonEmptyList[IndexName]) = {
    val singleIndex = indices.head
    val uuid = clusterService.indexOrAliasUuids(singleIndex).toList.head
    ReflectionUtils
      .getAllFields(request.shardId().getClass, ReflectionUtils.withName("index")).asScala
      .foldLeft(Try(())) {
        case (Success(_), field) =>
          field.setAccessible(true)
          Try(field.set(request.shardId(), new Index(singleIndex.value.value, uuid)))
        case (left, _) =>
          left
      }
  }

}
