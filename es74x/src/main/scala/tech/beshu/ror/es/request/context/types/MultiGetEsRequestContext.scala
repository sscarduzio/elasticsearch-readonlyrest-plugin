package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import cats.implicits._
import org.elasticsearch.action.get.MultiGetRequest
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

class MultiGetEsRequestContext(actionRequest: MultiGetRequest,
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
        modifyIndicesOf(actionRequest, nelOfIndices)
      case None =>
        ShouldBeInterrupted
    }
  }

  private def indicesFrom(request: MultiGetRequest) =
    request.getItems.asScala.flatMap(item => IndexName.fromString(item.index())).toSet

  private def modifyIndicesOf(request: MultiGetRequest,
                              nelOfIndices: NonEmptyList[IndexName]): ModificationResult = {
    request.getItems.removeIf { item => removeOrAlter(item, nelOfIndices.toList.toSet) }
    if (request.getItems.asScala.isEmpty) ShouldBeInterrupted
    else Modified
  }

  private def removeOrAlter(item: MultiGetRequest.Item,
                            filteredIndices: Set[IndexName]): Boolean = {
    val expandedIndicesOfRequest = clusterService.expandIndices(item.indices().asSafeSet.flatMap(IndexName.fromString))
    val remaining = expandedIndicesOfRequest.intersect(filteredIndices).toList
    remaining match {
      case Nil =>
        true
      case index :: rest =>
        if (rest.nonEmpty) {
          logger.warn(s"[${id.show}] Filter result contains more than one index. First was taken. Whole set of indices [${remaining.mkString(",")}]")
        }
        item.index(index.value.value)
        false
    }
  }
}
