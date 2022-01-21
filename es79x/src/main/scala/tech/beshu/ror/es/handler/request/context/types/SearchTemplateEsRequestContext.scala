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
import org.elasticsearch.action.{ActionRequest, ActionResponse, CompositeIndicesRequest}
import org.elasticsearch.search.builder.SearchSourceBuilder
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControl.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.Strategy.BasedOnBlockContextOnly
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, FieldLevelSecurity, Filter}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.response.SearchHitOps._
import tech.beshu.ror.es.handler.request.SearchRequestOps._
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.utils.ScalaOps._

class SearchTemplateEsRequestContext private(actionRequest: ActionRequest with CompositeIndicesRequest,
                                             esContext: EsContext,
                                             aclContext: AccessControlStaticContext,
                                             clusterService: RorClusterService,
                                             override implicit val threadPool: ThreadPool)
  extends BaseFilterableEsRequestContext[ActionRequest with CompositeIndicesRequest](
    actionRequest, esContext, aclContext, clusterService, threadPool
  ) {

  private lazy val searchTemplateRequest = new ReflectionBasedSearchTemplateRequest(actionRequest)
  private lazy val searchRequest = searchTemplateRequest.getRequest

  override protected def requestFieldsUsage: FieldLevelSecurity.RequestFieldsUsage =
    searchTemplateRequest.getRequest.checkFieldsUsage()

  override protected def indicesFrom(request: ActionRequest with CompositeIndicesRequest): Set[ClusterIndexName] = {
    searchRequest
      .indices.asSafeSet
      .flatMap(ClusterIndexName.fromString)
  }

  override protected def update(request: ActionRequest with CompositeIndicesRequest,
                                indices: NonEmptyList[ClusterIndexName],
                                filter: Option[domain.Filter],
                                fieldLevelSecurity: Option[domain.FieldLevelSecurity]): ModificationResult = {
    searchTemplateRequest.setRequest(
      searchRequest, indices, filter, fieldLevelSecurity
    )
    ModificationResult.UpdateResponse.using(filterFieldsFromResponse(fieldLevelSecurity))
  }

  private def filterFieldsFromResponse(fieldLevelSecurity: Option[FieldLevelSecurity])
                                      (actionResponse: ActionResponse): ActionResponse = {
    val searchTemplateResponse = new ReflectionBasedSearchTemplateResponse(actionResponse)
    (searchTemplateResponse.getResponse, fieldLevelSecurity) match {
      case (Some(response), Some(FieldLevelSecurity(restrictions, _: BasedOnBlockContextOnly))) =>
        response.getHits.getHits
          .foreach { hit =>
            hit
              .filterSourceFieldsUsing(restrictions)
              .filterDocumentFieldsUsing(restrictions)
          }
        actionResponse
      case _ =>
        actionResponse
    }
  }
}

object SearchTemplateEsRequestContext {
  def unapply(arg: ReflectionBasedActionRequest): Option[SearchTemplateEsRequestContext] = {
    if (arg.esContext.actionRequest.getClass.getSimpleName.startsWith("SearchTemplateRequest")) {
      Some(new SearchTemplateEsRequestContext(
        arg.esContext.actionRequest.asInstanceOf[ActionRequest with CompositeIndicesRequest],
        arg.esContext,
        arg.aclContext,
        arg.clusterService,
        arg.threadPool
      ))
    } else {
      None
    }
  }
}

final class ReflectionBasedSearchTemplateRequest(actionRequest: ActionRequest)
                                                (implicit threadPool: ThreadPool,
                                                 requestId: RequestContext.Id) {

  import org.joor.Reflect.on

  def getRequest: SearchRequest = {
    Option(on(actionRequest)
      .call("getRequest")
      .get[SearchRequest]) match {
      case Some(sr) => sr
      case None =>
        val sr = new SearchRequest("*")
        setSearchRequest(sr)
        sr
    }
  }

  def setRequest(searchRequest: SearchRequest,
                 indices: NonEmptyList[ClusterIndexName],
                 filter: Option[domain.Filter],
                 fieldLevelSecurity: Option[domain.FieldLevelSecurity]): Unit = {
    setSearchRequest(new EnhancedSearchRequest(searchRequest, indices, filter, fieldLevelSecurity))
  }

  private def setSearchRequest(searchRequest: SearchRequest) = {
    on(actionRequest).call("setRequest", searchRequest)
  }

  private class EnhancedSearchRequest(request: SearchRequest,
                                      indices: NonEmptyList[ClusterIndexName],
                                      filter: Option[Filter],
                                      fieldLevelSecurity: Option[FieldLevelSecurity])
                                     (implicit threadPool: ThreadPool,
                                      requestId: RequestContext.Id)
    extends SearchRequest(request) {

    this.indices(indices.toList.map(_.stringify): _*)

    override def source(sourceBuilder: SearchSourceBuilder): SearchRequest = {
      super
        .source(sourceBuilder)
        .applyFilterToQuery(filter)
        .applyFieldLevelSecurity(fieldLevelSecurity)
    }
  }
}

final class ReflectionBasedSearchTemplateResponse(actionResponse: ActionResponse) {

  import org.joor.Reflect.on

  def getResponse: Option[SearchResponse] = {
    Option(
      on(actionResponse)
        .call("getResponse")
        .get[SearchResponse]
    )
  }
}
