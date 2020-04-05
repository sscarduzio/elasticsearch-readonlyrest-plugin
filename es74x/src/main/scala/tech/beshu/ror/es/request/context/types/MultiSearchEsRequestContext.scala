package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.search.MultiSearchRequest
import org.elasticsearch.search.builder.SearchSourceBuilder
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult.{Modified, ShouldBeInterrupted}
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}
import tech.beshu.ror.utils.ScalaOps._

import scala.collection.JavaConverters._

class MultiSearchEsRequestContext(actionRequest: MultiSearchRequest,
                                  esContext: EsContext,
                                  clusterService: RorClusterService,
                                  override val threadPool: ThreadPool)
  extends BaseEsRequestContext[GeneralIndexRequestBlockContext](esContext, clusterService)
    with EsRequest[GeneralIndexRequestBlockContext]
    with Logging {

  override val initialBlockContext: GeneralIndexRequestBlockContext = GeneralIndexRequestBlockContext(
    this,
    UserMetadata.empty,
    Set.empty,
    Set.empty,
    indicesFrom(actionRequest)
  )

  override protected def modifyRequest(blockContext: GeneralIndexRequestBlockContext): ModificationResult = {
    optionallyDisableCaching()
    NonEmptyList.fromList(blockContext.indices.toList) match {
      case Some(nelOfIndices) =>
        modifyIndicesOf(actionRequest, nelOfIndices)
      case None =>
        ShouldBeInterrupted
    }
  }

  private def indicesFrom(request: MultiSearchRequest) =
    request.requests().asScala.flatMap(_.indices.asSafeSet.flatMap(IndexName.fromString)).toSet

  // Cache disabling for this request is crucial for document level security to work.
  // Otherwise we'd get an answer from the cache some times and would not be filtered
  private def optionallyDisableCaching(): Unit = {
    if (esContext.involveFilters) {
      logger.debug("ACL involves filters, will disable request cache for MultiSearchRequest")
      actionRequest.requests().asScala.foreach(_.requestCache(false))
    }
  }

  private def modifyIndicesOf(request: MultiSearchRequest,
                              nelOfIndices: NonEmptyList[IndexName]): ModificationResult = {
    request.requests().asScala.foreach { sr =>
      if (sr.indices.asSafeSet.isEmpty || sr.indices.asSafeSet.contains("*")) {
        sr.indices(nelOfIndices.toList.map(_.value.value): _*)
      } else {
        // This transforms wildcards and aliases in concrete indices
        val expandedSrIndices = clusterService.expandIndices(sr.indices().asSafeSet.flatMap(IndexName.fromString))
        val remaining = expandedSrIndices.intersect(nelOfIndices.toList.toSet)

        if (remaining.isEmpty) { // contained just forbidden indices, should return zero results
          sr.source(new SearchSourceBuilder().size(0))
        } else if (remaining.size == expandedSrIndices.size) { // contained all allowed indices
          // nothing to do
        } else {
          // some allowed indices were there, restrict query to those
          sr.indices(remaining.toList.map(_.value.value): _*)
        }
      }
    }
    if (request.requests().asScala.isEmpty) ShouldBeInterrupted
    else Modified
  }
}
