package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import org.elasticsearch.action.DocWriteRequest
import org.elasticsearch.action.bulk.BulkRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.{Modified, ShouldBeInterrupted}

import scala.collection.JavaConverters._

class BulkEsRequestContext(actionRequest: BulkRequest,
                           esContext: EsContext,
                           clusterService: RorClusterService,
                           override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[BulkRequest](actionRequest, esContext, clusterService, threadPool) {

  override protected def indicesFrom(request: BulkRequest): Set[IndexName] = {
    request.requests().asScala.map(_.index()).flatMap(IndexName.fromString).toSet
  }

  override protected def update(request: BulkRequest, indices: NonEmptyList[IndexName]): ModificationResult = {
    request.requests().removeIf { request: DocWriteRequest[_] => removeOrAlter(request, indices) }
    if(request.requests().asScala.isEmpty) ShouldBeInterrupted
    else Modified
  }

  private def removeOrAlter(request: DocWriteRequest[_], filteredIndices: NonEmptyList[IndexName]) = {
    val expandedIndicesOfRequest = clusterService.expandIndices(IndexName.fromString(request.index()).toSet)
    val remaining = expandedIndicesOfRequest.intersect(filteredIndices.toList.toSet).toList
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
             |Picking first from [${remaining.mkString(",")}]"""".stripMargin
        )
        false
    }
  }
}
