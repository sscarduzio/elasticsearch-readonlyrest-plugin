package tech.beshu.ror.es.request.context.types

import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.search.MultiSearchRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
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
    indicesFromRequest
  )

  override protected def modifyRequest(blockContext: GeneralIndexRequestBlockContext): ModificationResult = {
    optionallyDisableCaching()
    ???
  }

  private def indicesFromRequest =
    actionRequest.requests().asScala.flatMap(_.indices.asSafeSet.flatMap(IndexName.fromString)).toSet

  // Cache disabling for this request is crucial for document level security to work.
  // Otherwise we'd get an answer from the cache some times and would not be filtered
  private def optionallyDisableCaching(): Unit = {
    if (esContext.involveFilters) {
      logger.debug("ACL involves filters, will disable request cache for MultiSearchRequest")
      actionRequest.requests().asScala.foreach(_.requestCache(false))
    }
  }

}
