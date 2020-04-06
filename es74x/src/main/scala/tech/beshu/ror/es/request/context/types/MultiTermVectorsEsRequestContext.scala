package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import cats.implicits._
import org.elasticsearch.action.termvectors.{MultiTermVectorsRequest, TermVectorsRequest}
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.{Modified, ShouldBeInterrupted}

import scala.collection.JavaConverters._

class MultiTermVectorsEsRequestContext(actionRequest: MultiTermVectorsRequest,
                                       esContext: EsContext,
                                       clusterService: RorClusterService,
                                       override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[MultiTermVectorsRequest](actionRequest, esContext, clusterService, threadPool) {

  override protected def indicesFrom(request: MultiTermVectorsRequest): Set[IndexName] = {
    request.getRequests.asScala.flatMap(r => IndexName.fromString(r.index())).toSet
  }

  override protected def update(request: MultiTermVectorsRequest, indices: NonEmptyList[IndexName]): ModificationResult = {
    request.getRequests.removeIf { request => removeOrAlter(request, indices.toList.toSet) }
    if (request.getRequests.asScala.isEmpty) ShouldBeInterrupted
    else Modified
  }

  private def removeOrAlter(request: TermVectorsRequest,
                            filteredIndices: Set[IndexName]): Boolean = {
    val expandedIndicesOfRequest = clusterService.expandIndices(IndexName.fromString(request.index()).toSet)
    val remaining = expandedIndicesOfRequest.intersect(filteredIndices).toList
    remaining match {
      case Nil =>
        true
      case index :: rest =>
        if (rest.nonEmpty) {
          logger.warn(s"[${id.show}] Filter result contains more than one index. First was taken. Whole set of indices [${remaining.mkString(",")}]")
        }
        request.index(index.value.value)
        false
    }
  }
}
