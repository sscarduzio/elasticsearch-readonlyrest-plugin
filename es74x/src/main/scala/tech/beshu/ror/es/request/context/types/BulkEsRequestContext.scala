package tech.beshu.ror.es.request.context.types

import cats.implicits._
import org.elasticsearch.action.DocWriteRequest
import org.elasticsearch.action.bulk.BulkRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult.{Modified, ShouldBeInterrupted}
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}
import tech.beshu.ror.utils.ScalaOps._

import scala.collection.JavaConverters._

class BulkEsRequestContext(actionRequest: BulkRequest,
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
    actionRequest.requests().removeIf { request: DocWriteRequest[_] => removeOrAlter(request, blockContext.indices) }
    if(actionRequest.requests().asScala.isEmpty) ShouldBeInterrupted
    else Modified
  }

  private def indicesFromRequest =
    actionRequest.requests().asScala.map(_.index()).flatMap(IndexName.fromString).toSet

  private def removeOrAlter(request: DocWriteRequest[_], filteredIndices: Set[IndexName]) = {
    val expandedIndicesOfRequest = clusterService.expandIndices(request.indices.asSafeSet.flatMap(IndexName.fromString))
    val remaining = expandedIndicesOfRequest.intersect(filteredIndices).toList
    remaining match {
      case Nil =>
        true
      case one :: Nil =>
        request.index(one.value.value)
        false
      case one :: _ =>
        request.index(one.value.value)
        logger.warn(
          s"""[$taskId] One of requests from BulkOperation contains more than one index after expanding and intersect.
             |Picking first from [${remaining.map(_.show).mkString(",")}]"""".stripMargin
        )
        false
    }
  }
}
