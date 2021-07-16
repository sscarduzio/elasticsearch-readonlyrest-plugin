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
package tech.beshu.ror.es.handler.request.context.types

import cats.data.NonEmptyList
import org.elasticsearch.action.admin.cluster.repositories.get.GetRepositoriesRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain.RepositoryName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.ModificationResult.Modified
import tech.beshu.ror.utils.ScalaOps._

class GetRepositoriesEsRequestContext(actionRequest: GetRepositoriesRequest,
                                      esContext: EsContext,
                                      clusterService: RorClusterService,
                                      override val threadPool: ThreadPool)
  extends BaseRepositoriesEsRequestContext(actionRequest, esContext, clusterService, threadPool) {

  override protected def repositoriesFrom(request: GetRepositoriesRequest): Set[RepositoryName] = {
    repositoriesOrWildcard {
      request.repositories().asSafeSet.flatMap(RepositoryName.from)
    }
  }

  override protected def update(request: GetRepositoriesRequest,
                                repositories: NonEmptyList[RepositoryName]): ModificationResult = {
    request.repositories(repositories.map(RepositoryName.toString).toList.toArray)
    Modified
  }
}