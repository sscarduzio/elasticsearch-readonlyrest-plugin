package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.threadpool.ThreadPool
import org.joor.Reflect._
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.Modified

class GetRollupIndexCapsEsRequestContext private(actionRequest: ActionRequest,
                                                 esContext: EsContext,
                                                 aclContext: AccessControlStaticContext,
                                                 clusterService: RorClusterService,
                                                 override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[ActionRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def indicesFrom(request: ActionRequest): Set[IndexName] = {
    val indicesName = on(request).call("indices").get[Array[String]]()
    indicesName.flatMap(IndexName.fromString).toSet
  }

  override protected def update(request: ActionRequest, indices: NonEmptyList[IndexName]): ModificationResult = {
    on(request).call("indices", indices.map(_.value.value).toList.toArray)
    Modified
  }
}

object GetRollupIndexCapsEsRequestContext {
  def from(actionRequest: ActionRequest,
           esContext: EsContext,
           aclContext: AccessControlStaticContext,
           clusterService: RorClusterService,
           threadPool: ThreadPool): Option[GetRollupIndexCapsEsRequestContext] = {
    if (actionRequest.getClass.getName.endsWith("GetRollupIndexCapsAction$Request")) {
      Some(new GetRollupIndexCapsEsRequestContext(actionRequest, esContext, aclContext, clusterService, threadPool))
    } else {
      None
    }
  }
}