package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import cats.implicits._
import org.elasticsearch.action.bulk.BulkShardRequest
import org.elasticsearch.index.Index
import org.elasticsearch.threadpool.ThreadPool
import org.reflections.ReflectionUtils
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
                                clusterService: RorClusterService,
                                override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[BulkShardRequest](actionRequest, esContext, clusterService, threadPool) {

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
