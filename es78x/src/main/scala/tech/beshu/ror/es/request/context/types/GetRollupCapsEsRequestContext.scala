package tech.beshu.ror.es.request.context.types

import org.elasticsearch.action.ActionRequest
import org.elasticsearch.threadpool.ThreadPool
import org.joor.Reflect._
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.RequestSeemsToBeInvalid
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.Modified

class GetRollupCapsEsRequestContext private(actionRequest: ActionRequest,
                                           esContext: EsContext,
                                           aclContext: AccessControlStaticContext,
                                           clusterService: RorClusterService,
                                           override val threadPool: ThreadPool)
  extends BaseSingleIndexEsRequestContext[ActionRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def indexFrom(request: ActionRequest): IndexName = {
    val indexStr = on(request).call("getIndexPattern").get[String]()
    IndexName.fromString(indexStr) match {
      case Some(index) => index
      case None =>
        throw new RequestSeemsToBeInvalid[ActionRequest]("Cannot get non-empty index pattern from GetRollupCapsAction$Request")
    }
  }

  override protected def update(request: ActionRequest, index: IndexName): ModificationResult = {
    on(request).set("indexPattern", index.value.value)
    Modified
  }
}

object GetRollupCapsEsRequestContext {

  def unapply(arg: ReflectionBasedActionRequest): Option[GetRollupCapsEsRequestContext] = {
    if (arg.esContext.getClass.getName.endsWith("GetRollupCapsAction$Request")) {
      Some(new GetRollupCapsEsRequestContext(arg.esContext.actionRequest, arg.esContext, arg.aclContext, arg.clusterService, arg.threadPool))
    } else {
      None
    }
  }
}