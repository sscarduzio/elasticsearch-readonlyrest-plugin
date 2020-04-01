package tech.beshu.ror.es.request.context.types

import cats.implicits._
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult.ShouldBeInterrupted
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}

abstract class BaseSingleIndexEsRequestContext[R <: ActionRequest](actionRequest: R,
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
    Set(indexFrom(actionRequest))
  )

  override protected def modifyRequest(blockContext: GeneralIndexRequestBlockContext): ModificationResult = {
    indexFrom(blockContext) match {
      case Right(index) =>
        update(actionRequest, index)
        ModificationResult.Modified
      case Left(_) =>
        ShouldBeInterrupted
    }
  }

  private def indexFrom(blockContext: GeneralIndexRequestBlockContext) = {
    val indices = blockContext.indices.toList
    indices match {
      case Nil =>
        Left(())
      case index :: rest =>
        if (rest.nonEmpty) {
          logger.warn(s"[${id.show}] Filter result contains more than one index. First was taken. Whole set of indices [${indices.mkString(",")}]")
        }
        Right(index)
    }
  }

  protected def indexFrom(request: R): IndexName

  protected def update(request: R, index: IndexName): Unit
}
