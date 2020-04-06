package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.{Modified, ShouldBeInterrupted}
import tech.beshu.ror.utils.ScalaOps._

import scala.collection.JavaConverters._

class IndicesAliasesEsRequestContext(actionRequest: IndicesAliasesRequest,
                                     esContext: EsContext,
                                     clusterService: RorClusterService,
                                     override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[IndicesAliasesRequest](actionRequest, esContext, clusterService, threadPool) {

  override protected def indicesFrom(request: IndicesAliasesRequest): Set[IndexName] = {
    request.getAliasActions.asScala.flatMap(_.indices.asSafeSet.flatMap(IndexName.fromString)).toSet
  }

  override protected def update(request: IndicesAliasesRequest,
                                indices: NonEmptyList[IndexName]): ModificationResult = {
    request.getAliasActions.removeIf { action => removeOrAlter(action, indices.toList.toSet) }
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
