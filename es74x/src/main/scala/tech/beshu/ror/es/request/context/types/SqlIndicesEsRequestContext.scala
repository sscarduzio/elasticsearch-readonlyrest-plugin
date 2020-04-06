package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import org.elasticsearch.action.{ActionRequest, CompositeIndicesRequest}
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.RequestSeemsToBeInvalid
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.{CannotModify, Modified}
import tech.beshu.ror.es.utils.SqlRequestHelper

import scala.util.{Failure, Success}

class SqlIndicesEsRequestContext private(actionRequest: ActionRequest with CompositeIndicesRequest,
                                         esContext: EsContext,
                                         clusterService: RorClusterService,
                                         override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[ActionRequest with CompositeIndicesRequest](actionRequest, esContext, clusterService, threadPool) {

  private val sqlIndices = SqlRequestHelper
    .indicesFrom(actionRequest)
    .getOrElse(throw RequestSeemsToBeInvalid[CompositeIndicesRequest](s"Cannot extract SQL indices from ${actionRequest.getClass.getName}"))

  override protected def indicesFrom(request: ActionRequest with CompositeIndicesRequest): Set[IndexName] =
    sqlIndices.indices.flatMap(IndexName.fromString)

  override protected def update(request: ActionRequest with CompositeIndicesRequest,
                                indices: NonEmptyList[IndexName]): ModificationResult = {
    val indicesStrings = indices.map(_.value.value).toList.toSet
    if (indicesStrings != sqlIndices.indices) {
      SqlRequestHelper.modifyIndicesOf(request, sqlIndices, indicesStrings) match {
        case Success(_) =>
          Modified
        case Failure(ex) =>
          logger.error("Cannot modify SQL indices of incoming request", ex)
          CannotModify
      }
    } else {
      Modified
    }
  }
}

object SqlIndicesEsRequestContext {
  def from(actionRequest: ActionRequest with CompositeIndicesRequest,
           esContext: EsContext,
           clusterService: RorClusterService,
           threadPool: ThreadPool): Option[SqlIndicesEsRequestContext] = {
    if (esContext.channel.request().path().startsWith("/_sql"))
      Some(new SqlIndicesEsRequestContext(actionRequest, esContext, clusterService, threadPool))
    else
      None
  }
}