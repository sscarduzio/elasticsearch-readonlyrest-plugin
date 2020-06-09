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
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.get.{GetRequest, GetResponse}
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.action.search.SearchResponse
import org.elasticsearch.action.{ActionListener, ActionResponse}
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.FilterableRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.{Filter, IndexName}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.DocumentApiOps.GetApi._
import tech.beshu.ror.es.request.DocumentApiOps.{GetApi, createSearchRequest}
import tech.beshu.ror.es.request.RequestSeemsToBeInvalid
import tech.beshu.ror.es.request.context.ModificationResult.{Modified, ShouldBeInterrupted}
import tech.beshu.ror.es.request.context.types.GetEsRequestContext.FilteringResponseListener
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}

class GetEsRequestContext(actionRequest: GetRequest,
                          esContext: EsContext,
                          aclContext: AccessControlStaticContext,
                          clusterService: RorClusterService,
                          nodeClient: NodeClient,
                          override val threadPool: ThreadPool)
  extends BaseEsRequestContext[FilterableRequestBlockContext](esContext, clusterService)
    with EsRequest[FilterableRequestBlockContext] {

  override val initialBlockContext: FilterableRequestBlockContext = FilterableRequestBlockContext(
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

  override protected def modifyRequest(blockContext: FilterableRequestBlockContext): ModificationResult = {
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
    val filteringListener = new FilteringResponseListener(
      esContext.listener,
      nodeClient,
      filter,
      id
    )
    ModificationResult.CustomListener(filteringListener)
  }
}

object GetEsRequestContext {

  private final class FilteringResponseListener(underlying: ActionListener[ActionResponse],
                                                nodeClient: NodeClient,
                                                filter: Option[Filter],
                                                id: RequestContext.Id)
    extends ActionListener[ActionResponse] with Logging {

    override def onFailure(e: Exception): Unit = underlying.onFailure(e)

    override def onResponse(actionResponse: ActionResponse): Unit = {
      (actionResponse, filter) match {
        case (response: GetResponse, Some(definedFilter)) if response.isExists =>
          verifyDocumentAccessibility(response, definedFilter)
        case (other, _) =>
          underlying.onResponse(other)
      }
    }

    private def verifyDocumentAccessibility(originalResponse: GetResponse,
                                            definedFilter: Filter) = {
      createSearchRequest(nodeClient, definedFilter)(originalResponse.asDocumentWithIndex)
        .execute(new ActionListener[SearchResponse] {

          override def onFailure(exception: Exception): Unit = {
            logger.error(s"[${id.show}] Search request failed, could not verify get request. Blocking document", exception)
            underlying.onResponse(GetApi.doesNotExistResponse(originalResponse))
          }

          override def onResponse(searchResponse: SearchResponse): Unit = {
            if (searchResponse.getHits.getTotalHits.value == 0L) {
              underlying.onResponse(GetApi.doesNotExistResponse(originalResponse))
            } else {
              underlying.onResponse(originalResponse)
            }
          }
        })
    }
  }
}