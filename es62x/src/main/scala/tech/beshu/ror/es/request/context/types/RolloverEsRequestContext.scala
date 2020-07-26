package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import org.elasticsearch.action.admin.indices.rollover.RolloverRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.Modified
import org.joor.Reflect._

class RolloverEsRequestContext(actionRequest: RolloverRequest,
                               esContext: EsContext,
                               aclContext: AccessControlStaticContext,
                               clusterService: RorClusterService,
                               override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[RolloverRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def indicesFrom(request: RolloverRequest): Set[IndexName] = {
    (Option(getNewIndexNameFrom(request)).toSet ++ Set(getAliasFrom(request)))
      .flatMap(IndexName.fromString)
  }

  override protected def update(request: RolloverRequest,
                                indices: NonEmptyList[IndexName]): ModificationResult = {
    Modified
  }

  private def getNewIndexNameFrom(request: RolloverRequest) = {
    on(request).call("getNewIndexName").get[String]
  }

  private def getAliasFrom(request: RolloverRequest) = {
    on(request).call("getAlias").get[String]
  }
}
