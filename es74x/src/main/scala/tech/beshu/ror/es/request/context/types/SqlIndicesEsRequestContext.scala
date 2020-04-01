package tech.beshu.ror.es.request.context.types

import org.elasticsearch.action.{ActionRequest, CompositeIndicesRequest}
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}
import tech.beshu.ror.es.utils.SqlRequestHelper

import scala.util.{Failure, Success}

class SqlIndicesEsRequestContext private(actionRequest: ActionRequest with CompositeIndicesRequest,
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

  override protected def modifyRequest(blockContext: GeneralIndexRequestBlockContext): ModificationResult = ???

  private def indicesFromRequest = {
    SqlRequestHelper.indicesFrom(actionRequest) match {
      case Success(indices) => indices.indices.flatMap(IndexName.fromString)
      case Failure(ex) =>
        throw new IllegalArgumentException(s"Cannot process SQL request - ${actionRequest.getClass.getName}", ex)
    }
  }
}

object SqlIndicesEsRequestContext {
  def from(actionRequest: ActionRequest with CompositeIndicesRequest,
           esContext: EsContext,
           clusterService: RorClusterService,
           threadPool: ThreadPool): Option[SqlIndicesEsRequestContext] = {
    if (esContext.channel.request().path().startsWith("/_sql"))
      Some(new SqlIndicesEsRequestContext(actionRequest, esContext, clusterService, threadPool))
    else
      None
  }
}