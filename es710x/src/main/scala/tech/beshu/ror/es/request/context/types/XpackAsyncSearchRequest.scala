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
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.accesscontrol.{AccessControlStaticContext, domain}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.RequestSeemsToBeInvalid
import tech.beshu.ror.es.request.SearchRequestOps._
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.context.ModificationResult.Modified
import tech.beshu.ror.utils.ReflecUtils.invokeMethodCached
import tech.beshu.ror.utils.ScalaOps._

class XpackAsyncSearchRequest private(actionRequest: ActionRequest,
                                      esContext: EsContext,
                                      aclContext: AccessControlStaticContext,
                                      clusterService: RorClusterService,
                                      override val threadPool: ThreadPool)
  extends BaseFilterableEsRequestContext[ActionRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  private lazy val searchRequest = searchRequestFrom(actionRequest)

  override protected def indicesFrom(request: ActionRequest): Set[domain.IndexName] = {
    searchRequest
      .indices.asSafeSet
      .flatMap(IndexName.fromString)
  }

  override protected def update(request: ActionRequest,
                                indices: NonEmptyList[domain.IndexName],
                                filter: Option[domain.Filter]): ModificationResult = {
    optionallyDisableCaching(searchRequest)
    searchRequest
      .applyFilterToQuery(filter)
      .indices(indices.toList.map(_.value.value): _*)
    Modified
  }

  private def searchRequestFrom(request: ActionRequest) = {
    Option(invokeMethodCached(request, request.getClass, "getSearchRequest"))
      .collect { case sr: SearchRequest => sr }
      .getOrElse(throw new RequestSeemsToBeInvalid[ActionRequest]("Cannot extract SearchRequest from SubmitAsyncSearchRequest request"))
  }

  //TODO cache is now disabled only when 'fields' rule is used.
  //Remove after 'fields' rule improvements.
  private def optionallyDisableCaching(request: SearchRequest): Unit = {
    if (esContext.involvesFields) {
      logger.debug("ACL involves fields, will disable request cache for SearchRequest")
      request.requestCache(false)
    }
  }
}

object XpackAsyncSearchRequest {

  def unapply(arg: ReflectionBasedActionRequest): Option[XpackAsyncSearchRequest] = {
    if (arg.esContext.actionRequest.getClass.getSimpleName.startsWith("SubmitAsyncSearchRequest")) {
      Some(new XpackAsyncSearchRequest(arg.esContext.actionRequest, arg.esContext, arg.aclContext, arg.clusterService, arg.threadPool))
    } else {
      None
    }
  }
}
