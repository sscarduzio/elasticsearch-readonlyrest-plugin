package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import org.elasticsearch.action.{ActionRequest, IndicesRequest}
import org.elasticsearch.action.IndicesRequest.Replaceable
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.Modified
import tech.beshu.ror.utils.ScalaOps._

class GetAliasesEsRequestContext(actionRequest: GetAliasesRequest,
                                 esContext: EsContext,
                                 aclContext: AccessControlStaticContext,
                                 clusterService: RorClusterService,
                                 override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[GetAliasesRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def indicesFrom(request: GetAliasesRequest): Set[IndexName] = {
    request.aliases().asSafeSet ++ request.indices().as
    request.asInstanceOf[IndicesRequest].indices.asSafeSet.flatMap(IndexName.fromString)
  }

  override protected def update(request: GetAliasesRequest,
                                indices: NonEmptyList[IndexName]): ModificationResult = {
    request.indices(indices.toList.map(_.value.value): _*)
    Modified
  }
}
