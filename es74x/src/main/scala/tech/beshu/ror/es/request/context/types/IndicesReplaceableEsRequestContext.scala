package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import org.elasticsearch.action.{ActionRequest, IndicesRequest}
import org.elasticsearch.action.IndicesRequest.Replaceable
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.Modified
import tech.beshu.ror.utils.ScalaOps._

class IndicesReplaceableEsRequestContext(actionRequest: ActionRequest with Replaceable,
                                         esContext: EsContext,
                                         clusterService: RorClusterService,
                                         override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[ActionRequest with Replaceable](actionRequest, esContext, clusterService, threadPool) {

  override protected def indicesFrom(request: ActionRequest with Replaceable): Set[IndexName] = {
    request.asInstanceOf[IndicesRequest].indices.asSafeSet.flatMap(IndexName.fromString)
  }

  override protected def update(request: ActionRequest with Replaceable,
                                indices: NonEmptyList[IndexName]): ModificationResult = {
    request.indices(indices.toList.map(_.value.value): _*)
    Modified
  }
}
