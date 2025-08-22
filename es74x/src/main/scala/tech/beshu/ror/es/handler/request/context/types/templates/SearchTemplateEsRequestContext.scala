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
package tech.beshu.ror.es.handler.request.context.types.templates

import cats.data.NonEmptyList
import monix.eval.Task
import monix.execution.{CancelablePromise, Scheduler}
import org.elasticsearch.action.search.{SearchRequest, SearchResponse}
import org.elasticsearch.action.{ActionListener, ActionRequest, ActionResponse, CompositeIndicesRequest}
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlList.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.Strategy.BasedOnBlockContextOnly
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, FieldLevelSecurity, RequestedIndex}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.SearchRequestOps.*
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.request.context.types.{BaseFilterableEsRequestContext, ReflectionBasedActionRequest}
import tech.beshu.ror.es.handler.response.SearchHitOps.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*

class SearchTemplateEsRequestContext private(actionRequest: ActionRequest with CompositeIndicesRequest,
                                             esContext: EsContext,
                                             aclContext: AccessControlStaticContext,
                                             clusterService: RorClusterService,
                                             nodeClient: NodeClient,
                                             override implicit val threadPool: ThreadPool)
                                            (implicit scheduler: Scheduler)
  extends BaseFilterableEsRequestContext[ActionRequest with CompositeIndicesRequest](
    actionRequest, esContext, aclContext, clusterService, threadPool
  ) {

  private lazy val searchTemplateRequest = new ReflectionBasedSearchTemplateRequest(actionRequest)
  private lazy val searchRequest = searchTemplateRequest.getRequest

  override protected def requestFieldsUsage: FieldLevelSecurity.RequestFieldsUsage =
    searchTemplateRequest.getRequest.checkFieldsUsage()

  override protected def requestedIndicesFrom(request: ActionRequest with CompositeIndicesRequest): Set[RequestedIndex[ClusterIndexName]] = {
    searchRequest
      .indices.asSafeSet
      .flatMap(RequestedIndex.fromString)
  }

  override protected def update(request: ActionRequest with CompositeIndicesRequest,
                                filteredRequestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]],
                                filter: Option[domain.Filter],
                                fieldLevelSecurity: Option[domain.FieldLevelSecurity]): ModificationResult = {
    searchRequest.indices(filteredRequestedIndices.stringify: _*)
    if (searchTemplateRequest.isSimulate)
      ModificationResult.UpdateResponse.sync { resp =>
        filterFieldsFromResponse(fieldLevelSecurity)(new ReflectionBasedSearchTemplateResponse(resp))
      }
    else
      ModificationResult.UpdateResponse.async(callSearchOnceAgain(filter, fieldLevelSecurity))
  }

  /*
   * this is a hack, because in old version there is no way to extend ES SearchRequest and provide different behaviour
   * of `source(...)` method. We have to do that, because in method `convert` of `TransportSearchTemplateAction` search
   * source is created from params and script and applied to current search request. In the next step we have to apply
   * out filter and field level security. It is easy to overcome in new ES versions, but in old ones, due to mentioned
   * final modifier, we are forced to do it in the other way - by calling search again when we get the response. This
   * solution is obviously less efficient, but at least it works.
   */
  private def callSearchOnceAgain(filter: Option[domain.Filter],
                                  fieldLevelSecurity: Option[domain.FieldLevelSecurity]): ActionResponse => Task[ActionResponse] = {
    searchTemplateResponse => {
      val updatedSearchRequest = searchRequest
        .applyFilterToQuery(filter)
        .applyFieldLevelSecurity(fieldLevelSecurity)
      search(updatedSearchRequest)
        .map { searchResponse =>
          val reflectionBasedSearchTemplateResponse = new ReflectionBasedSearchTemplateResponse(searchTemplateResponse)
          reflectionBasedSearchTemplateResponse.setResponse(searchResponse)
          filterFieldsFromResponse(fieldLevelSecurity)(reflectionBasedSearchTemplateResponse)
        }
    }
  }

  private def filterFieldsFromResponse(fieldLevelSecurity: Option[FieldLevelSecurity])
                                      (response: ReflectionBasedSearchTemplateResponse): ActionResponse = {
    (response.getResponse, fieldLevelSecurity) match {
      case (Some(r), Some(FieldLevelSecurity(restrictions, _: BasedOnBlockContextOnly))) =>
        r.getHits.getHits
          .foreach { hit =>
            hit
              .filterSourceFieldsUsing(restrictions)
              .filterDocumentFieldsUsing(restrictions)
          }
        response.underlying
      case _ =>
        response.underlying
    }
  }

  private def search(request: SearchRequest): Task[SearchResponse] = {
    val promise = CancelablePromise[SearchResponse]()
    nodeClient.search(request, new ActionListener[SearchResponse]() {
      override def onResponse(response: SearchResponse): Unit = promise.trySuccess(response)
      override def onFailure(e: Exception): Unit = promise.tryFailure(e)
    })
    Task.fromCancelablePromise(promise)
  }

}

object SearchTemplateEsRequestContext {
  def unapply(arg: ReflectionBasedActionRequest)
             (implicit scheduler: Scheduler): Option[SearchTemplateEsRequestContext] = {
    if (arg.esContext.actionRequest.getClass.getSimpleName.startsWith("SearchTemplateRequest")) {
      Some(new SearchTemplateEsRequestContext(
        arg.esContext.actionRequest.asInstanceOf[ActionRequest with CompositeIndicesRequest],
        arg.esContext,
        arg.aclContext,
        arg.clusterService,
        arg.nodeClient,
        arg.threadPool
      ))
    } else {
      None
    }
  }
}

final class ReflectionBasedSearchTemplateRequest(underlying: ActionRequest) {

  import org.joor.Reflect.on

  def isSimulate: Boolean = {
    on(underlying).call("isSimulate").get[Boolean]
  }

  def getRequest: SearchRequest = {
    Option(on(underlying)
      .call("getRequest")
      .get[SearchRequest]) match {
      case Some(sr) => sr
      case None =>
        val sr = new SearchRequest("*")
        setSearchRequest(sr)
        sr
    }
  }

  private def setSearchRequest(searchRequest: SearchRequest) = {
    on(underlying).call("setRequest", searchRequest)
  }

}

final class ReflectionBasedSearchTemplateResponse(val underlying: ActionResponse) {

  import org.joor.Reflect.on

  def getResponse: Option[SearchResponse] = {
    Option(
      on(underlying)
        .call("getResponse")
        .get[SearchResponse]
    )
  }

  def setResponse(response: SearchResponse): Unit = {
    on(underlying).call("setResponse", response)
  }
}
