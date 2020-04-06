package tech.beshu.ror.es.request.context.types

import org.elasticsearch.action.ActionRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.SnapshotRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.{IndexName, RepositoryName, SnapshotName}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest}

abstract class BaseSnapshotEsRequestContext[T <: ActionRequest](actionRequest: T,
                                                                esContext: EsContext,
                                                                clusterService: RorClusterService,
                                                                override val threadPool: ThreadPool)
  extends BaseEsRequestContext[SnapshotRequestBlockContext](esContext, clusterService)
    with EsRequest[SnapshotRequestBlockContext] {

  override val initialBlockContext: SnapshotRequestBlockContext = SnapshotRequestBlockContext(
    this,
    UserMetadata.from(this),
    Set.empty,
    Set.empty,
    snapshotsFrom(actionRequest),
    repositoriesFrom(actionRequest),
    indicesFrom(actionRequest)
  )

  protected def snapshotsFrom(request: T): Set[SnapshotName]

  protected def repositoriesFrom(request: T): Set[RepositoryName]

  protected def indicesFrom(request: T): Set[IndexName]
}
