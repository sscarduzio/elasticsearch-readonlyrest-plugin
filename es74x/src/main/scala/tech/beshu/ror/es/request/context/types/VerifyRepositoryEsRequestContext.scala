package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import cats.implicits._
import eu.timepit.refined.types.string.NonEmptyString
import org.elasticsearch.action.admin.cluster.repositories.verify.VerifyRepositoryRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain.RepositoryName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.RequestSeemsToBeInvalid
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.Modified

class VerifyRepositoryEsRequestContext(actionRequest: VerifyRepositoryRequest,
                                       esContext: EsContext,
                                       clusterService: RorClusterService,
                                       override val threadPool: ThreadPool)
  extends BaseRepositoriesEsRequestContext(actionRequest, esContext, clusterService, threadPool) {

  override protected def repositoriesFrom(request: VerifyRepositoryRequest): Set[RepositoryName] = Set{
    NonEmptyString
      .from(request.name())
      .map(RepositoryName.apply)
      .fold(
        msg => throw RequestSeemsToBeInvalid[VerifyRepositoryRequest](msg),
        identity
      )
  }

  override protected def update(request: VerifyRepositoryRequest,
                                repositories: NonEmptyList[RepositoryName]): ModificationResult = {
    if (repositories.tail.nonEmpty) {
      logger.warn(s"[${id.show}] Filter result contains more than one repository. First was taken. Whole set of repositories [${repositories.toList.mkString(",")}]")
    }
    request.name(repositories.head.value.value)
    Modified
  }
}
