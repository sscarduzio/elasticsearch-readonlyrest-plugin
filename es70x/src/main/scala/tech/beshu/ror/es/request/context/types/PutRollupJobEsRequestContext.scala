package tech.beshu.ror.es.request.context.types

import cats.implicits._
import cats.data.NonEmptyList
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.{AccessControlStaticContext, domain}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import org.joor.Reflect._
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.request.context.ModificationResult.{Modified, ShouldBeInterrupted}

class PutRollupJobEsRequestContext private(actionRequest: ActionRequest,
                                           esContext: EsContext,
                                           aclContext: AccessControlStaticContext,
                                           clusterService: RorClusterService,
                                           override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[ActionRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  private lazy val originIndices = {
    val config = on(actionRequest).call("getConfig").get[AnyRef]()
    val indexPattern = on(config).call("getIndexPattern").get[String]()
    val rollupIndex = on(config).call("getRollupIndex").get[String]()
    (IndexName.fromString(indexPattern) :: IndexName.fromString(rollupIndex) :: Nil).flatten.toSet
  }

  override protected def indicesFrom(request: ActionRequest): Set[domain.IndexName] = originIndices

  override protected def update(request: ActionRequest, indices: NonEmptyList[domain.IndexName]): ModificationResult = {
    if(originIndices == indices.toList.toSet) {
      Modified
    } else {
      logger.error(s"[${id.show}] Write request with indices requires the same set of indices after filtering as at the beginning. Please report the issue.")
      ShouldBeInterrupted
    }
  }
}

object PutRollupJobEsRequestContext {

  def unapply(arg: ReflectionBasedActionRequest): Option[PutRollupJobEsRequestContext] = {
    if (arg.esContext.actionRequest.getClass.getName.endsWith("PutRollupJobAction$Request")) {
      Some(new PutRollupJobEsRequestContext(arg.esContext.actionRequest, arg.esContext, arg.aclContext, arg.clusterService, arg.threadPool))
    } else {
      None
    }
  }
}