package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import com.google.common.collect.Sets
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult.{Modified, ShouldBeInterrupted}
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}
import tech.beshu.ror.utils.ReflecUtils
import tech.beshu.ror.utils.ReflecUtils.extractStringArrayFromPrivateMethod
import tech.beshu.ror.utils.ScalaOps._
import scala.collection.JavaConverters._

class ReflectionBasedIndicesEsRequestContext private(actionRequest: ActionRequest,
                                                     indices: Set[IndexName],
                                                     esContext: EsContext,
                                                     clusterService: RorClusterService,
                                                     override val threadPool: ThreadPool)
  extends BaseEsRequestContext[GeneralIndexRequestBlockContext](esContext, clusterService)
    with EsRequest[GeneralIndexRequestBlockContext] {

  override val initialBlockContext: GeneralIndexRequestBlockContext = GeneralIndexRequestBlockContext(
    this,
    UserMetadata.empty,
    Set.empty,
    Set.empty,
    indices
  )

  override protected def modifyRequest(blockContext: GeneralIndexRequestBlockContext): ModificationResult = {
    NonEmptyList.fromList(blockContext.indices.toList) match {
      case Some(nelOfIndices) =>
        if(tryUpdate(actionRequest, nelOfIndices)) Modified
        else ShouldBeInterrupted
      case None =>
        ShouldBeInterrupted
    }
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