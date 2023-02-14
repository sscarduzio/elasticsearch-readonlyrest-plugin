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
import org.elasticsearch.action.search.{SearchRequest, SearchResponse}
import org.elasticsearch.action.{ActionRequest, ActionResponse}
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControl.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, FieldLevelSecurity, IndexAttribute}
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.RequestSeemsToBeInvalid
import tech.beshu.ror.es.handler.response.SearchHitOps._
import tech.beshu.ror.es.handler.request.SearchRequestOps._
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.utils.ReflecUtils.invokeMethodCached
import tech.beshu.ror.utils.ScalaOps._

class XpackAsyncSearchRequestContext private(actionRequest: ActionRequest,
                                             esContext: EsContext,
                                             aclContext: AccessControlStaticContext,
                                             clusterService: RorClusterService,
                                             override implicit val threadPool: ThreadPool)
  extends BaseFilterableEsRequestContext[ActionRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  private lazy val searchRequest = searchRequestFrom(actionRequest)

  override lazy val indexAttributes: Set[IndexAttribute] = indexAttributesFrom(searchRequest)

  override protected def requestFieldsUsage: RequestFieldsUsage = searchRequest.checkFieldsUsage()

  override protected def indicesFrom(request: ActionRequest): Set[domain.ClusterIndexName] = {
    searchRequest
      .indices.asSafeSet
      .flatMap(ClusterIndexName.fromString)
  }

  override protected def update(request: ActionRequest,
                                indices: NonEmptyList[domain.ClusterIndexName],
                                filter: Option[domain.Filter],
                                fieldLevelSecurity: Option[FieldLevelSecurity]): ModificationResult = {
    searchRequest
      .applyFilterToQuery(filter)
      .applyFieldLevelSecurity(fieldLevelSecurity)
      .indices(indices.toList.map(_.stringify): _*)

    ModificationResult.UpdateResponse.using(filterFieldsFromResponse(fieldLevelSecurity))
  }

  private def searchRequestFrom(request: ActionRequest) = {
    Option(invokeMethodCached(request, request.getClass, "getSearchRequest"))
      .collect { case sr: SearchRequest => sr }
      .getOrElse(throw new RequestSeemsToBeInvalid[ActionRequest]("Cannot extract SearchRequest from SubmitAsyncSearchRequest request"))
  }

  private def filterFieldsFromResponse(fieldLevelSecurity: Option[FieldLevelSecurity])
                                      (actionResponse: ActionResponse): ActionResponse = {
    (searchResponseFrom(actionResponse), fieldLevelSecurity) match {
      case (Some(searchResponse), Some(definedFieldLevelSecurity)) =>
        searchResponse.getHits.getHits
          .foreach { hit =>
            hit
              .filterSourceFieldsUsing(definedFieldLevelSecurity.restrictions)
              .filterDocumentFieldsUsing(definedFieldLevelSecurity.restrictions)
          }
        actionResponse
      case _ =>
        actionResponse
    }
  }

  private def searchResponseFrom(response: ActionResponse) = {
    Option(invokeMethodCached(response, response.getClass, "getSearchResponse"))
      .collect { case sr: SearchResponse => sr }
  }
}

object XpackAsyncSearchRequestContext {

  def unapply(arg: ReflectionBasedActionRequest): Option[XpackAsyncSearchRequestContext] = {
    if (arg.esContext.actionRequest.getClass.getSimpleName.startsWith("SubmitAsyncSearchRequest")) {
      Some(new XpackAsyncSearchRequestContext(arg.esContext.actionRequest, arg.esContext, arg.aclContext, arg.clusterService, arg.threadPool))
    } else {
      None
    }
  }
}
