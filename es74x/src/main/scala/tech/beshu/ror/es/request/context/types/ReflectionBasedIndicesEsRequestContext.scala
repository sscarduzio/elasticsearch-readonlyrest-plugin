package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import com.google.common.collect.Sets
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.{Modified, ShouldBeInterrupted}
import tech.beshu.ror.utils.ReflecUtils
import tech.beshu.ror.utils.ReflecUtils.extractStringArrayFromPrivateMethod
import tech.beshu.ror.utils.ScalaOps._

import scala.collection.JavaConverters._

class ReflectionBasedIndicesEsRequestContext private(actionRequest: ActionRequest,
                                                     indices: Set[IndexName],
                                                     esContext: EsContext,
                                                     clusterService: RorClusterService,
                                                     override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[ActionRequest](actionRequest, esContext, clusterService, threadPool) {

  override protected def indicesFrom(request: ActionRequest): Set[IndexName] = indices

  override protected def update(request: ActionRequest, indices: NonEmptyList[IndexName]): ModificationResult = {
    if (tryUpdate(actionRequest, indices)) Modified
    else ShouldBeInterrupted
  }

  private def tryUpdate(actionRequest: ActionRequest, indices: NonEmptyList[IndexName]) = {
    // Optimistic reflection attempt
    ReflecUtils.setIndices(
      actionRequest,
      Sets.newHashSet("index", "indices"),
      indices.toList.map(_.value.value).toSet.asJava
    )
  }
}

object ReflectionBasedIndicesEsRequestContext {

  def from(actionRequest: ActionRequest,
           esContext: EsContext,
           clusterService: RorClusterService,
           threadPool: ThreadPool): Option[ReflectionBasedIndicesEsRequestContext] = {
    indicesFrom(actionRequest)
      .map(new ReflectionBasedIndicesEsRequestContext(actionRequest, _, esContext, clusterService, threadPool))
  }

  private def indicesFrom(request: ActionRequest) = {
    NonEmptyList
      .fromList(extractStringArrayFromPrivateMethod("indices", request).asSafeList)
      .orElse(NonEmptyList.fromList(extractStringArrayFromPrivateMethod("index", request).asSafeList))
      .map(indices => indices.toList.toSet.flatMap(IndexName.fromString))
  }
}