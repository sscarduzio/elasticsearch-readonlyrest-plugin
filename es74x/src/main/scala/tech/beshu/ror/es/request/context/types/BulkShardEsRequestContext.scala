package tech.beshu.ror.es.request.context.types

import cats.implicits._
import cats.data.NonEmptyList
import org.elasticsearch.action.bulk.BulkShardRequest
import org.elasticsearch.index.Index
import org.elasticsearch.threadpool.ThreadPool
import org.reflections.ReflectionUtils
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult.{CannotModify, Modified, ShouldBeInterrupted}
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}
import tech.beshu.ror.utils.ScalaOps._

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

class BulkShardEsRequestContext(actionRequest: BulkShardRequest,
                                esContext: EsContext,
                                clusterService: RorClusterService,
                                override val threadPool: ThreadPool)
  extends BaseEsRequestContext[GeneralIndexRequestBlockContext](esContext, clusterService)
    with EsRequest[GeneralIndexRequestBlockContext] {

  override val initialBlockContext: GeneralIndexRequestBlockContext = GeneralIndexRequestBlockContext(
    this,
    UserMetadata.empty,
    Set.empty,
    Set.empty,
    indicesFrom(actionRequest)
  )

  override protected def modifyRequest(blockContext: GeneralIndexRequestBlockContext): ModificationResult = {
    NonEmptyList.fromList(blockContext.indices.toList) match {
      case Some(nelOfIndices) =>
        update(actionRequest, nelOfIndices) match {
          case Success(_) =>
            Modified
          case Failure(ex) =>
            logger.error(s"[${id.show}] Cannot modify BulkShardRequest", ex)
            CannotModify
        }
      case None =>
        ShouldBeInterrupted
    }
  }

  private def indicesFrom(request: BulkShardRequest): Set[IndexName] = {
    request.indices().asSafeSet.flatMap(IndexName.fromString)
  }

  private def update(request: BulkShardRequest, indices: NonEmptyList[IndexName]) = {
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
