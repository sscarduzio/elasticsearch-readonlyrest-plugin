package tech.beshu.ror.es.request.context.types

import org.elasticsearch.action.ActionRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.SnapshotRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.{IndexName, RepositoryName, SnapshotName}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}

abstract class BaseSnapshotEsRequestContext[T <: ActionRequest](actionRequest: T,
                                                                esContext: EsContext,
                                                                clusterService: RorClusterService,
                                                                override val threadPool: ThreadPool)
  extends BaseEsRequestContext[SnapshotRequestBlockContext](esContext, clusterService)
    with EsRequest[SnapshotRequestBlockContext] {

  override val initialBlockContext: SnapshotRequestBlockContext = SnapshotRequestBlockContext(
    this,
    UserMetadata.empty,
    Set.empty,
    Set.empty,
    snapshotsFromRequest,
    repositoriesFromRequest,
    indicesFromRequest
  )

  protected def snapshotsFromRequest: Set[SnapshotName]

  protected def repositoriesFromRequest: Set[RepositoryName]

  protected def indicesFromRequest: Set[IndexName]
}
