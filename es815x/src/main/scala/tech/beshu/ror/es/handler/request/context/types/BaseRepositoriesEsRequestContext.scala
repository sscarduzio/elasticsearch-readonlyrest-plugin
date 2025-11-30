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
import cats.implicits.*
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.RepositoryRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.RepositoryName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult.ShouldBeInterrupted
import tech.beshu.ror.es.handler.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*

abstract class BaseRepositoriesEsRequestContext[R <: ActionRequest](actionRequest: R,
                                                                    esContext: EsContext,
                                                                    clusterService: RorClusterService,
                                                                    override val threadPool: ThreadPool)
  extends BaseEsRequestContext[RepositoryRequestBlockContext](esContext, clusterService)
    with EsRequest[RepositoryRequestBlockContext] {

  override val initialBlockContext: RepositoryRequestBlockContext = RepositoryRequestBlockContext(
    requestContext = this,
    userMetadata = UserMetadata.from(this),
    responseHeaders = Set.empty,
    responseTransformations = List.empty,
    repositories = repositoriesFrom(actionRequest).orWildcardWhenEmpty
  )

  override protected def modifyRequest(blockContext: RepositoryRequestBlockContext): ModificationResult = {
    NonEmptyList.fromList(blockContext.repositories.toList) match {
      case Some(repositories) =>
        update(actionRequest, repositories)
      case None =>
        logger.error(s"Cannot update ${actionRequest.getClass.show} request, because of empty repositories list.")
        ShouldBeInterrupted
    }
  }

  protected def repositoriesFrom(request: R): Set[RepositoryName]

  protected def update(request: R, repositories: NonEmptyList[RepositoryName]): ModificationResult
}

