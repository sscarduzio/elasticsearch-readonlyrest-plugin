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
import cats.implicits._
import monix.eval.Task
import monix.execution.CancelablePromise
import org.elasticsearch.action.search.{MultiSearchRequest, MultiSearchResponse, SearchRequest}
import org.elasticsearch.action.support.IndicesOptions
import org.elasticsearch.action.{ActionListener, ActionRequest, ActionResponse, CompositeIndicesRequest}
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.threadpool.ThreadPool
import org.joor.Reflect.on
import tech.beshu.ror.accesscontrol.blocks.BlockContext.FilterableMultiRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiIndexRequestBlockContext.Indices
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage.NotUsingFields
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.Strategy.BasedOnBlockContextOnly
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, FieldLevelSecurity, Filter}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.utils.IndicesListOps._
import tech.beshu.ror.accesscontrol.AccessControl.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.response.SearchHitOps._
import tech.beshu.ror.es.handler.request.SearchRequestOps._
import tech.beshu.ror.es.handler.request.context.ModificationResult.{Modified, ShouldBeInterrupted}
import tech.beshu.ror.es.handler.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}
import tech.beshu.ror.utils.ScalaOps._

class MultiSearchTemplateEsRequestContext private(actionRequest: ActionRequest with CompositeIndicesRequest,
                                                  esContext: EsContext,
                                                  aclContext: AccessControlStaticContext,
                                                  clusterService: RorClusterService,
                                                  nodeClient: NodeClient,
                                                  override implicit val threadPool: ThreadPool)
  extends BaseEsRequestContext[FilterableMultiRequestBlockContext](esContext, clusterService)
    with EsRequest[FilterableMultiRequestBlockContext] {

  override lazy val initialBlockContext: FilterableMultiRequestBlockContext = FilterableMultiRequestBlockContext(
    this,
    UserMetadata.from(this),
    Set.empty,
    List.empty,
    indexPacksFrom(multiSearchTemplateRequest),
    None,
    None,
    requestFieldsUsage
  )

  private lazy val multiSearchTemplateRequest = new ReflectionBasedMultiSearchTemplateRequest(actionRequest)

  override protected def modifyRequest(blockContext: FilterableMultiRequestBlockContext): ModificationResult = {
    val modifiedPacksOfIndices = blockContext.indexPacks
    val requests = multiSearchTemplateRequest.requests
    if (requests.size == modifiedPacksOfIndices.size) {
      requests
        .zip(modifiedPacksOfIndices)
        .foreach { case (request, pack) =>
          updateRequest(request, pack, blockContext.filter, blockContext.fieldLevelSecurity)
        }
      ModificationResult.UpdateResponse(
        callSearchOnceAgain(modifiedPacksOfIndices, blockContext.filter, blockContext.fieldLevelSecurity)
      )
    } else {
      logger.error(s"[${id.show}] Cannot alter MultiSearchRequest request, because origin request contained different number of" +
        s" inner requests, than altered one. This can be security issue. So, it's better for forbid the request")
      ShouldBeInterrupted
    }
  }

  /*
 * this is a hack, because in old version there is no way to extend ES SearchRequest and provide different behaviour
 * of `source(...)` method. We have to do that, because in method `convert` of `TransportSearchTemplateAction` search
 * source is created from params and script and applied to current search request. In the next step we have to apply
 * out filter and field level security. It is easy to overcome in new ES versions, but in old ones, due to mentioned
 * final modifier, we are forced to do it in the other way - by calling search again when we get the response. This
 * solution is obviously less efficient, but at least it works.
 */
  private def callSearchOnceAgain(indices: List[Indices],
                                  filter: Option[domain.Filter],
                                  fieldLevelSecurity: Option[domain.FieldLevelSecurity]): ActionResponse => Task[ActionResponse] = {
    multiSearchTemplateResponse => {
      val updatedSearchRequests = multiSearchTemplateRequest
        .requests
        .map(_
          .getRequest
          .applyFilterToQuery(filter)
          .applyFieldLevelSecurity(fieldLevelSecurity)
        )
      mSearch(updatedSearchRequests)
        .map { multiSearchResponse =>
          val reflectionBasedSearchTemplateResponse = new ReflectionBasedMultiSearchTemplateResponse(multiSearchTemplateResponse)
          reflectionBasedSearchTemplateResponse.updateUsing(multiSearchResponse)
          filterFieldsFromResponse(fieldLevelSecurity)(reflectionBasedSearchTemplateResponse)
        }
    }
  }

  private def requestFieldsUsage: RequestFieldsUsage = {
    NonEmptyList.fromList(multiSearchTemplateRequest.requests) match {
      case Some(definedRequests) =>
        definedRequests
          .map(_.getRequest.checkFieldsUsage())
          .combineAll
      case None =>
        NotUsingFields
    }
  }

  private def filterFieldsFromResponse(fieldLevelSecurity: Option[FieldLevelSecurity])
                                      (response: ReflectionBasedMultiSearchTemplateResponse): ActionResponse = {
    (response.getResponses, fieldLevelSecurity) match {
      case (responses, Some(FieldLevelSecurity(restrictions, _: BasedOnBlockContextOnly))) =>
        responses.map(_.map(_.getResponse)).foreach {
          case Right(Some(r)) =>
            r.getHits
              .getHits
              .foreach { hit =>
                hit
                  .filterSourceFieldsUsing(restrictions)
                  .filterDocumentFieldsUsing(restrictions)
              }
          case _ =>
        }
        response.actionResponse
      case _ =>
        response.actionResponse
    }
  }

  override def modifyWhenIndexNotFound: ModificationResult = {
    multiSearchTemplateRequest.requests.foreach(updateRequestWithNonExistingIndex)
    Modified
  }

  private def indexPacksFrom(request: ReflectionBasedMultiSearchTemplateRequest): List[Indices] = {
    request
      .requests
      .map { request => Indices.Found(indicesFrom(request)) }
  }

  private def updateRequest(request: ReflectionBasedSearchTemplateRequest,
                            indexPack: Indices,
                            filter: Option[Filter],
                            fieldLevelSecurity: Option[FieldLevelSecurity]): Unit = {
    val nonEmptyIndicesList = indexPack match {
      case Indices.Found(indices) =>
        NonEmptyList
          .fromList(indices.toList)
          .getOrElse(NonEmptyList.one(randomNonexistentIndex(request)))
      case Indices.Found(_) | Indices.NotFound =>
        NonEmptyList.one(randomNonexistentIndex(request))
    }
    request.getRequest.indices(nonEmptyIndicesList.toList.map(_.stringify): _*)
  }

  private def updateRequestWithNonExistingIndex(request: ReflectionBasedSearchTemplateRequest): Unit = {
    request.getRequest.indices(randomNonexistentIndex(request).stringify)
  }

  private def randomNonexistentIndex(request: ReflectionBasedSearchTemplateRequest) =
    indicesFrom(request).toList.randomNonexistentIndex()

  private def indicesFrom(request: ReflectionBasedSearchTemplateRequest) = {
    val requestIndices = request.getRequest.indices.asSafeSet.flatMap(ClusterIndexName.fromString)
    indicesOrWildcard(requestIndices)
  }

  private def mSearch(requests: List[SearchRequest]): Task[MultiSearchResponse] = {
    val promise = CancelablePromise[MultiSearchResponse]()
    val multiSearchRequest = new MultiSearchRequest()
    requests.foreach(multiSearchRequest.add)
    multiSearchRequest.indicesOptions(multiSearchTemplateRequest.indicesOptions())
    nodeClient.multiSearch(multiSearchRequest, new ActionListener[MultiSearchResponse]() {
      override def onResponse(response: MultiSearchResponse): Unit = promise.trySuccess(response)
      override def onFailure(e: Exception): Unit = promise.tryFailure(e)
    })
    Task.fromCancelablePromise(promise)
  }
}

object MultiSearchTemplateEsRequestContext {
  def unapply(arg: ReflectionBasedActionRequest): Option[MultiSearchTemplateEsRequestContext] = {
    if (arg.esContext.actionRequest.getClass.getSimpleName.startsWith("MultiSearchTemplateRequest")) {
      Some(new MultiSearchTemplateEsRequestContext(
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

private class ReflectionBasedMultiSearchTemplateRequest(val actionRequest: ActionRequest)
                                                       (implicit val requestContext: RequestContext.Id,
                                                        threadPool: ThreadPool) {

  import org.joor.Reflect.on

  def requests: List[ReflectionBasedSearchTemplateRequest] = {
    on(actionRequest)
      .call("requests")
      .get[java.util.List[ActionRequest]]
      .asSafeList
      .map(new ReflectionBasedSearchTemplateRequest(_))
  }

  def indicesOptions(): IndicesOptions = {
    on(actionRequest)
      .call("indicesOptions")
      .get[IndicesOptions]
  }
}

private class ReflectionBasedMultiSearchTemplateResponse(val actionResponse: ActionResponse) {

  import org.joor.Reflect.on

  def getResponses: List[Either[Throwable, ReflectionBasedSearchTemplateResponse]] = {
    on(actionResponse)
      .call("getResponses")
      .get[Array[AnyRef]]
      .asSafeList
      .map(itemToEither)
  }

  def updateUsing(multiSearchResponse: MultiSearchResponse): Unit = {
    getResponses
      .zip(multiSearchResponse.getResponses)
      .foreach {
        case (Right(_), currentSearchResponse) if currentSearchResponse.isFailure =>
          Left(currentSearchResponse.getFailure)
        case (Right(prevSearchTemplateResponse), currentSearchResponse) =>
          Right(prevSearchTemplateResponse.setResponse(currentSearchResponse.getResponse))
        case (Left(ex), _) =>
          Left(ex)
      }
  }

  private def itemToEither(item: AnyRef) = {
    ReflectionBasedItem.createFrom(item).getResponse
  }
}

private class ReflectionBasedItem private(value: AnyRef) {

  def getResponse: Either[Throwable, ReflectionBasedSearchTemplateResponse] = {
    Option(on(value).call("getResponse").get[ActionResponse]) match {
      case Some(response) => Right(new ReflectionBasedSearchTemplateResponse(response))
      case None => Left(on(value).call("getFailure").get[Throwable])
    }
  }
}
private object ReflectionBasedItem {
  def createFrom(value: AnyRef): ReflectionBasedItem = new ReflectionBasedItem(value)
}