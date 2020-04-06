package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import cats.implicits._
import org.elasticsearch.action.get.MultiGetRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.{Modified, ShouldBeInterrupted}

import scala.collection.JavaConverters._

class MultiGetEsRequestContext(actionRequest: MultiGetRequest,
                               esContext: EsContext,
                               clusterService: RorClusterService,
                               override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[MultiGetRequest](actionRequest, esContext, clusterService, threadPool) {

  override protected def indicesFrom(request: MultiGetRequest): Set[IndexName] = {
    request.getItems.asScala.flatMap(item => IndexName.fromString(item.index())).toSet
  }

  override protected def update(request: MultiGetRequest,
                                indices: NonEmptyList[IndexName]): ModificationResult = {
    request.getItems.removeIf { item => removeOrAlter(item, indices.toList.toSet) }
    if (request.getItems.asScala.isEmpty) ShouldBeInterrupted
    else Modified
  }

  private def removeOrAlter(item: MultiGetRequest.Item,
                            filteredIndices: Set[IndexName]): Boolean = {
    val expandedIndicesOfRequest = clusterService.expandIndices(IndexName.fromString(item.index()).toSet)
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