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
import org.elasticsearch.action.ActionResponse
import org.elasticsearch.action.search.{MultiSearchRequest, MultiSearchResponse, SearchRequest}
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.FilterableMultiRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiIndexRequestBlockContext.Indices
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage.NotUsingFields
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.Strategy.BasedOnBlockContextOnly
import tech.beshu.ror.accesscontrol.domain.{FieldLevelSecurity, Filter, ClusterIndexName}
import tech.beshu.ror.accesscontrol.utils.IndicesListOps._
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.SearchHitOps._
import tech.beshu.ror.es.handler.request.SearchRequestOps._
import tech.beshu.ror.es.handler.request.context.ModificationResult.{Modified, ShouldBeInterrupted}
import tech.beshu.ror.es.handler.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}
import tech.beshu.ror.utils.ScalaOps._

import scala.collection.JavaConverters._

class MultiSearchEsRequestContext(actionRequest: MultiSearchRequest,
                                  esContext: EsContext,
                                  aclContext: AccessControlStaticContext,
                                  clusterService: RorClusterService,
                                  override implicit val threadPool: ThreadPool)
  extends BaseEsRequestContext[FilterableMultiRequestBlockContext](esContext, clusterService)
    with EsRequest[FilterableMultiRequestBlockContext] {

  override lazy val initialBlockContext: FilterableMultiRequestBlockContext = FilterableMultiRequestBlockContext(
    this,
    UserMetadata.from(this),
    Set.empty,
    List.empty,
    indexPacksFrom(actionRequest),
    None,
    None,
    requestFieldsUsage
  )

  override protected def modifyRequest(blockContext: FilterableMultiRequestBlockContext): ModificationResult = {
    val modifiedPacksOfIndices = blockContext.indexPacks
    val requests = actionRequest.requests().asScala.toList
    if (requests.size == modifiedPacksOfIndices.size) {
      requests
        .zip(modifiedPacksOfIndices)
        .foreach { case (request, pack) =>
          updateRequest(request, pack, blockContext.filter, blockContext.fieldLevelSecurity)
        }
      ModificationResult.UpdateResponse.using(filterFieldsFromResponse(blockContext.fieldLevelSecurity))
    } else {
      logger.error(s"[${id.show}] Cannot alter MultiSearchRequest request, because origin request contained different number of" +
        s" inner requests, than altered one. This can be security issue. So, it's better for forbid the request")
      ShouldBeInterrupted
    }
  }

  private def requestFieldsUsage: RequestFieldsUsage = {
    NonEmptyList.fromList(actionRequest.requests().asScala.toList) match {
      case Some(definedRequests) =>
        definedRequests
          .map(_.checkFieldsUsage())
          .combineAll
      case None =>
        NotUsingFields
    }
  }

  private def filterFieldsFromResponse(fieldLevelSecurity: Option[FieldLevelSecurity])
                                      (actionResponse: ActionResponse): ActionResponse = {
    (actionResponse, fieldLevelSecurity) match {
      case (response: MultiSearchResponse, Some(FieldLevelSecurity(restrictions, _: BasedOnBlockContextOnly))) =>
        response.getResponses
          .filterNot(_.isFailure)
          .flatMap(_.getResponse.getHits.getHits)
          .foreach { hit =>
            hit
              .filterSourceFieldsUsing(restrictions)
              .filterDocumentFieldsUsing(restrictions)
          }
        response
      case _ =>
        actionResponse
    }
  }

  override def modifyWhenIndexNotFound: ModificationResult = {
    val requests = actionRequest.requests().asScala.toList
    requests.foreach(updateRequestWithNonExistingIndex)
    Modified
  }

  private def indexPacksFrom(request: MultiSearchRequest): List[Indices] = {
    request
      .requests().asScala
      .map { request => Indices.Found(indicesFrom(request)) }
      .toList
  }

  private def indicesFrom(request: SearchRequest) = {
    val requestIndices = request.indices.asSafeSet.flatMap(ClusterIndexName.fromString)
    indicesOrWildcard(requestIndices)
  }

  private def updateRequest(request: SearchRequest,
                            indexPack: Indices,
                            filter: Option[Filter],
                            fieldLevelSecurity: Option[FieldLevelSecurity]) = {
    indexPack match {
      case Indices.Found(indices) =>
        updateRequestWithIndices(request, indices)
      case Indices.NotFound =>
        updateRequestWithNonExistingIndex(request)
    }
    request
      .applyFilterToQuery(filter)
      .applyFieldLevelSecurity(fieldLevelSecurity)
  }

  private def updateRequestWithIndices(request: SearchRequest, indices: Set[ClusterIndexName]) = {
    indices.toList match {
      case Nil => updateRequestWithNonExistingIndex(request)
      case nonEmptyIndicesList => request.indices(nonEmptyIndicesList.map(_.stringify): _*)
    }
  }

  private def updateRequestWithNonExistingIndex(request: SearchRequest): Unit = {
    val originRequestIndices = indicesFrom(request).toList
    val notExistingIndex = originRequestIndices.randomNonexistentIndex()
    request.indices(notExistingIndex.stringify)
  }
}
