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
package tech.beshu.ror.es.request.context.types

import cats.data.NonEmptyList
import org.elasticsearch.action.ActionRequest
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.Modified
import tech.beshu.ror.utils.ReflecUtils.invokeMethodCached
import tech.beshu.ror.utils.ScalaOps._

import scala.util.Try

class SearchTemplateEsRequestContext private(actionRequest: ActionRequest,
                                             esContext: EsContext,
                                             aclContext: AccessControlStaticContext,
                                             clusterService: RorClusterService,
                                             override val threadPool: ThreadPool)
  extends BaseIndicesEsRequestContext[ActionRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  private lazy val searchRequest = Try(Option(invokeMethodCached(actionRequest, actionRequest.getClass, "getRequest")))
    .toOption.flatten
    .flatMap {
      case req: SearchRequest => Some(req)
      case _ => None
    }

  override protected def indicesFrom(request: ActionRequest): Set[IndexName] = {
    searchRequest
      .map(_.indices.asSafeSet)
      .getOrElse(Set.empty)
      .flatMap(IndexName.fromString)
  }

  override protected def update(request: ActionRequest, indices: NonEmptyList[IndexName]): ModificationResult = {
    searchRequest match {
      case Some(sr) =>
        sr.indices(indices.toList.map(_.value.value): _*)
        Modified
      case None =>
        Modified
    }
  }
}

object SearchTemplateEsRequestContext {
  def from(actionRequest: ActionRequest,
           esContext: EsContext,
           aclContext: AccessControlStaticContext,
           clusterService: RorClusterService,
           threadPool: ThreadPool): Option[SearchTemplateEsRequestContext] = {
    if (actionRequest.getClass.getSimpleName.startsWith("SearchTemplateRequest")) {
      Some(new SearchTemplateEsRequestContext(actionRequest, esContext, aclContext, clusterService, threadPool))
    } else {
      None
    }
  }
}