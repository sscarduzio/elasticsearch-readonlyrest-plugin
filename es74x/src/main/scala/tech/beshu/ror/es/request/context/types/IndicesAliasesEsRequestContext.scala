package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest
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

class IndicesAliasesEsRequestContext(actionRequest: IndicesAliasesRequest,
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

  private def indicesFrom(request: IndicesAliasesRequest) =
    request.getAliasActions.asScala.flatMap(_.indices.asSafeSet.flatMap(IndexName.fromString)).toSet

  private def modifyIndicesOf(request: IndicesAliasesRequest,
                              nelOfIndices: NonEmptyList[IndexName]): ModificationResult = {
    request.getAliasActions.removeIf { action => removeOrAlter(action, nelOfIndices.toList.toSet) }
    if (request.getAliasActions.asScala.isEmpty) ShouldBeInterrupted
    else Modified
  }

  private def removeOrAlter(action: IndicesAliasesRequest.AliasActions,
                            filteredIndices: Set[IndexName]): Boolean = {
    val expandedIndicesOfRequest = clusterService.expandIndices(action.indices().asSafeSet.flatMap(IndexName.fromString))
    val remaining = expandedIndicesOfRequest.intersect(filteredIndices).toList
    remaining match {
      case Nil =>
        true
      case indices =>
        action.indices(indices.map(_.value.value): _*)
        false
    }
  }

}
