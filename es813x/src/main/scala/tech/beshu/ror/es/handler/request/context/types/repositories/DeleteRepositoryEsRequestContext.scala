/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.es.handler.request.context.types.repositories

import cats.data.NonEmptyList
import cats.implicits._
import org.elasticsearch.action.admin.cluster.repositories.delete.DeleteRepositoryRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain.RepositoryName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.RequestSeemsToBeInvalid
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.ModificationResult.Modified
import tech.beshu.ror.es.handler.request.context.types.BaseRepositoriesEsRequestContext

class DeleteRepositoryEsRequestContext(actionRequest: DeleteRepositoryRequest,
                                       esContext: EsContext,
                                       clusterService: RorClusterService,
                                       override val threadPool: ThreadPool)
  extends BaseRepositoriesEsRequestContext(actionRequest, esContext, clusterService, threadPool) {

  override protected def repositoriesFrom(request: DeleteRepositoryRequest): Set[RepositoryName] = Set {
    RepositoryName
      .from(request.name())
      .getOrElse(throw RequestSeemsToBeInvalid[DeleteRepositoryRequest]("Repository name is empty"))
  }

  override protected def update(request: DeleteRepositoryRequest,
                                repositories: NonEmptyList[RepositoryName]): ModificationResult = {
    if (repositories.tail.nonEmpty) {
      logger.warn(s"[${id.show}] Filtered result contains more than one repository. First was taken. The whole set of repositories [${repositories.toList.mkString(",")}]")
    }
    request.name(RepositoryName.toString(repositories.head))
    Modified
  }
}
