package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import cats.implicits._
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult

abstract class BaseSingleIndexEsRequestContext[R <: ActionRequest](actionRequest: R,
                                                                   esContext: EsContext,
                                                                   clusterService: RorClusterService,
                                                                   override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[R](actionRequest, esContext, clusterService, threadPool) {

  override protected def indicesFrom(request: R): Set[IndexName] = Set(indexFrom(request))

  override protected def update(request: R, indices: NonEmptyList[IndexName]): ModificationResult = {
    if (indices.tail.nonEmpty) {
      logger.warn(s"[${id.show}] Filter result contains more than one index. First was taken. Whole set of indices [${indices.toList.mkString(",")}]")
    }
    update(request, indices.head)
  }

  protected def indexFrom(request: R): IndexName

  protected def update(request: R, index: IndexName): ModificationResult
}
