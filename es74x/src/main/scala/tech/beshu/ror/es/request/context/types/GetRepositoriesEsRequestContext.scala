package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import eu.timepit.refined.types.string.NonEmptyString
import org.elasticsearch.action.admin.cluster.repositories.get.GetRepositoriesRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain.RepositoryName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.Modified
import tech.beshu.ror.utils.ScalaOps._

class GetRepositoriesEsRequestContext(actionRequest: GetRepositoriesRequest,
                                      esContext: EsContext,
                                      clusterService: RorClusterService,
                                      override val threadPool: ThreadPool)
  extends BaseRepositoriesEsRequestContext(actionRequest, esContext, clusterService, threadPool) {

  override protected def repositoriesFrom(request: GetRepositoriesRequest): Set[RepositoryName] = {
    request.repositories().asSafeSet.flatMap(NonEmptyString.unapply).map(RepositoryName.apply)
  }

  override protected def update(request: GetRepositoriesRequest,
                                repositories: NonEmptyList[RepositoryName]): ModificationResult = {
    request.repositories(repositories.toList.map(_.value.value).toArray)
    Modified
  }
}