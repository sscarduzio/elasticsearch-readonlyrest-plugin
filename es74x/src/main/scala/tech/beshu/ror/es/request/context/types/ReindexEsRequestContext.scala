package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import cats.implicits._
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.index.reindex.ReindexRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult.{CannotModify, Modified, ShouldBeInterrupted}
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}
import tech.beshu.ror.utils.LoggerOps._
import tech.beshu.ror.utils.ReflecUtils.invokeMethodCached
import tech.beshu.ror.utils.ScalaOps._

import scala.util.{Failure, Success, Try}

class ReindexEsRequestContext(actionRequest: ReindexRequest,
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
    indicesFromRequest
  )

  override protected def modifyRequest(blockContext: GeneralIndexRequestBlockContext): ModificationResult = {
    NonEmptyList.fromList(blockContext.indices.toList) match {
      case Some(nelOfIndices) =>
        modifyIndicesOf(actionRequest, nelOfIndices) match {
          case Success(_) =>
            Modified
          case Failure(ex) =>
            logger.error(s"[${id.show}] Cannot modify ReindexRequest", ex)
            CannotModify
        }
      case None =>
        ShouldBeInterrupted
    }
  }

  private def indicesFromRequest = {
    Try {
      val sr = invokeMethodCached(actionRequest, actionRequest.getClass, "getSearchRequest").asInstanceOf[SearchRequest]
      val ir = invokeMethodCached(actionRequest, actionRequest.getClass, "getDestination").asInstanceOf[IndexRequest]
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

  private def modifyIndicesOf(actionRequest: ReindexRequest, indices: NonEmptyList[IndexName]) = {
    Try {
      val sr = invokeMethodCached(actionRequest, actionRequest.getClass, "getSearchRequest").asInstanceOf[SearchRequest]
      val expandedIndicesOfSearchRequest = clusterService.expandIndices(sr.indices().asSafeSet.flatMap(IndexName.fromString))
      val remainingSearchIndices = expandedIndicesOfSearchRequest.intersect(indices.toList.toSet).toList
      sr.indices(remainingSearchIndices.map(_.value.value): _*)

      val ir = invokeMethodCached(actionRequest, actionRequest.getClass, "getDestination").asInstanceOf[IndexRequest]
      val expandedDestinationIndices = clusterService.expandIndices(IndexName.fromString(ir.index()).toSet)
      val remainingDestinationIndices = expandedDestinationIndices.intersect(indices.toList.toSet).toList
      ir.index(remainingDestinationIndices.head.value.value)
    }
  }
}
