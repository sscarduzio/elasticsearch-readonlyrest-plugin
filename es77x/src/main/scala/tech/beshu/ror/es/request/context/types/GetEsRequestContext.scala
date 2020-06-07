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
import cats.implicits._
import org.elasticsearch.action.ActionResponse
import org.elasticsearch.action.get.{GetRequest, GetResponse}
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.action.search.SearchResponse
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GetEsRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.{Filter, IndexName}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.DocumentApiOps.DocumentAccessibility.{Accessible, Inaccessible}
import tech.beshu.ror.es.request.DocumentApiOps.GetApi._
import tech.beshu.ror.es.request.DocumentApiOps.{GetApi, createSearchRequest}
import tech.beshu.ror.es.request.RequestSeemsToBeInvalid
import tech.beshu.ror.es.request.context.ModificationResult.{Modified, ShouldBeInterrupted}
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}

import scala.util.{Failure, Success, Try}

class GetEsRequestContext(actionRequest: GetRequest,
                          esContext: EsContext,
                          aclContext: AccessControlStaticContext,
                          clusterService: RorClusterService,
                          nodeClient: NodeClient,
                          override val threadPool: ThreadPool)
  extends BaseEsRequestContext[GetEsRequestBlockContext](esContext, clusterService)
    with EsRequest[GetEsRequestBlockContext] {

  override val initialBlockContext: GetEsRequestBlockContext = GetEsRequestBlockContext(
    this,
    UserMetadata.from(this),
    Set.empty,
    Set.empty,
    {
      import tech.beshu.ror.accesscontrol.show.logs._
      val indices = indicesOrWildcard(indicesFrom(actionRequest))
      logger.debug(s"[${id.show}] Discovered indices: ${indices.map(_.show).mkString(",")}")
      indices
    },
    None
  )

  override def modifyWhenIndexNotFound: ModificationResult = {
    if (aclContext.doesRequirePassword) {
      val nonExistentIndex = initialBlockContext.randomNonexistentIndex()
      if (nonExistentIndex.hasWildcard) {
        val nonExistingIndices = NonEmptyList
          .fromList(initialBlockContext.nonExistingIndicesFromInitialIndices().toList)
          .getOrElse(NonEmptyList.of(nonExistentIndex))
        update(actionRequest, nonExistingIndices, initialBlockContext.filter)
        Modified
      } else {
        ShouldBeInterrupted
      }
    } else {
      update(actionRequest, NonEmptyList.of(initialBlockContext.randomNonexistentIndex()), initialBlockContext.filter)
      Modified
    }
  }

  override protected def modifyRequest(blockContext: GetEsRequestBlockContext): ModificationResult = {
    NonEmptyList.fromList(blockContext.indices.toList) match {
      case Some(indices) =>
        update(actionRequest, indices, blockContext.filter)
      case None =>
        logger.warn(s"[${id.show}] empty list of indices produced, so we have to interrupt the request processing")
        ShouldBeInterrupted
    }
  }

  private def indicesFrom(request: GetRequest): Set[IndexName] = {
    val indexName = IndexName
      .fromString(request.index())
      .getOrElse {
        throw RequestSeemsToBeInvalid[IndexRequest]("Index name is invalid")
      }
    Set(indexName)
  }

  private def update(request: GetRequest,
                     indices: NonEmptyList[IndexName],
                     filter: Option[Filter]): ModificationResult = {
    val indexName = indices.head
    request.index(indexName.value.value)
    ModificationResult.UpdateResponse(filterResponse(filter, indexName, request.id()))
  }

  private def filterResponse(filter: Option[Filter],
                             indexName: IndexName,
                             documentId: String)
                            (actionResponse: ActionResponse): ActionResponse = {
    actionResponse match {
      case response: GetResponse =>
        filter match {
          case Some(definedFilter) if response.isExists =>
            verifyDocumentAccessibility(response, definedFilter) match {
              case Inaccessible => GetApi.doesNotExistResponse(response)
              case Accessible => response
            }
          case _ => response
        }
      case other => other
    }
  }

  private def verifyDocumentAccessibility(originalResponse: GetResponse,
                                          definedFilter: Filter) = {
    val searchRequest = createSearchRequest(nodeClient, definedFilter)(originalResponse.asDocumentWithIndex)
    Try(searchRequest.get()) match {
      case Failure(exception) =>
        logger.error(s"Could not verify get request. Blocking document", exception)
        Inaccessible
      case Success(searchResponse) =>
        compareOriginalResponseWithSearchResult(searchResponse, originalResponse)
    }
  }

  private def compareOriginalResponseWithSearchResult(searchResponse: SearchResponse,
                                                      original: GetResponse) = {
    if (searchResponse.getHits.getTotalHits.value == 0L) Inaccessible
    else Accessible
  }
}