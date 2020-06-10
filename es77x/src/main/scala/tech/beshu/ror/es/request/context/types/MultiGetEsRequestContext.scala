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
import org.elasticsearch.action.get.{MultiGetItemResponse, MultiGetRequest, MultiGetResponse}
import org.elasticsearch.action.search.{MultiSearchRequestBuilder, MultiSearchResponse}
import org.elasticsearch.action.{ActionListener, ActionResponse}
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.FilterableMultiRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiIndexRequestBlockContext.Indices
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.DocumentAccessibility.{Accessible, Inaccessible}
import tech.beshu.ror.accesscontrol.domain.{DocumentAccessibility, DocumentWithIndex, Filter, IndexName}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.utils.IndicesListOps._
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.DocumentApiOps.MultiGetApi._
import tech.beshu.ror.es.request.DocumentApiOps.{GetApi, createSearchRequest}
import tech.beshu.ror.es.request.context.ModificationResult.ShouldBeInterrupted
import tech.beshu.ror.es.request.context.types.MultiGetEsRequestContext.FilteringResponseListener
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}

import scala.collection.JavaConverters._

class MultiGetEsRequestContext(actionRequest: MultiGetRequest,
                               esContext: EsContext,
                               clusterService: RorClusterService,
                               nodeClient: NodeClient,
                               override val threadPool: ThreadPool)
  extends BaseEsRequestContext[FilterableMultiRequestBlockContext](esContext, clusterService)
    with EsRequest[FilterableMultiRequestBlockContext] {

  override lazy val initialBlockContext: FilterableMultiRequestBlockContext = FilterableMultiRequestBlockContext(
    this,
    UserMetadata.from(this),
    Set.empty,
    Set.empty,
    indexPacksFrom(actionRequest),
    None
  )

  override protected def modifyRequest(blockContext: FilterableMultiRequestBlockContext): ModificationResult = {
    val modifiedPacksOfIndices = blockContext.indexPacks
    val items = actionRequest.getItems.asScala.toList
    if (items.size == modifiedPacksOfIndices.size) {
      items
        .zip(modifiedPacksOfIndices)
        .foreach { case (item, pack) =>
          updateItem(item, pack)
        }
      val filteringListener = new FilteringResponseListener(
        esContext.listener,
        nodeClient,
        blockContext.filter,
        id
      )
      ModificationResult.CustomListener(filteringListener)
    } else {
      logger.error(
        s"""[${id.show}] Cannot alter MultiGetRequest request, because origin request contained different
           |number of items, than altered one. This can be security issue. So, it's better for forbid the request""".stripMargin)
      ShouldBeInterrupted
    }
  }

  private def indexPacksFrom(request: MultiGetRequest): List[Indices] = {
    request
      .getItems.asScala
      .map { item => Indices.Found(indicesFrom(item)) }
      .toList
  }

  private def indicesFrom(item: MultiGetRequest.Item): Set[domain.IndexName] = {
    val requestIndices = item.indices.flatMap(IndexName.fromString).toSet
    indicesOrWildcard(requestIndices)
  }

  private def updateItem(item: MultiGetRequest.Item, indexPack: Indices): Unit = {
    indexPack match {
      case Indices.Found(indices) =>
        updateItemWithIndices(item, indices)
      case Indices.NotFound =>
        updateItemWithNonExistingIndex(item)
    }
  }

  private def updateItemWithIndices(item: MultiGetRequest.Item, indices: Set[IndexName]) = {
    indices.toList match {
      case Nil => updateItemWithNonExistingIndex(item)
      case index :: rest =>
        if (rest.nonEmpty) {
          logger.warn(s"[${id.show}] Filtered result contains more than one index. First was taken. Whole set of indices [${indices.toList.mkString(",")}]")
        }
        item.index(index.value.value)
    }
  }

  private def updateItemWithNonExistingIndex(item: MultiGetRequest.Item): Unit = {
    val originRequestIndices = indicesFrom(item).toList
    val notExistingIndex = originRequestIndices.randomNonexistentIndex()
    item.index(notExistingIndex.value.value)
  }
}

object MultiGetEsRequestContext {

  private final class FilteringResponseListener(underlying: ActionListener[ActionResponse],
                                                nodeClient: NodeClient,
                                                filter: Option[Filter],
                                                id: RequestContext.Id)
    extends ActionListener[ActionResponse] with Logging {

    override def onFailure(e: Exception): Unit = underlying.onFailure(e)

    override def onResponse(actionResponse: ActionResponse): Unit = {
      (actionResponse, filter) match {
        case (response: MultiGetResponse, Some(definedFilter)) =>
          applyFilter(response, definedFilter)
        case (other, _) =>
          underlying.onResponse(other)
      }
    }

    private def applyFilter(response: MultiGetResponse, definedFilter: Filter) = {
      val originalResponses = response.getResponses.toList

      NonEmptyList.fromList(identifyDocumentsToVerifyUsing(originalResponses)) match {
        case Some(existingDocumentsToVerify) =>
          val request = createMSearchRequest(definedFilter, existingDocumentsToVerify)
          executeMultiSearch(existingDocumentsToVerify, request, originalResponses)
        case None =>
          underlying.onResponse(response)
      }
    }

    private def identifyDocumentsToVerifyUsing(itemResponses: List[MultiGetItemResponse]) = {
      itemResponses
        .filter(requiresAdditionalVerification)
        .map(_.asDocumentWithIndex)
        .distinct
    }

    private def requiresAdditionalVerification(item: MultiGetItemResponse) = {
      !item.isFailed && item.getResponse.isExists
    }

    private def createMSearchRequest(definedFilter: Filter,
                                     docsToVerify: NonEmptyList[DocumentWithIndex]) = {
      docsToVerify
        .map(createSearchRequest(nodeClient, definedFilter))
        .foldLeft(nodeClient.prepareMultiSearch())(_ add _)
    }

    private def executeMultiSearch(docsToVerify: NonEmptyList[DocumentWithIndex],
                                   mSearchRequest: MultiSearchRequestBuilder,
                                   originalResponses: List[MultiGetItemResponse]) = {
      mSearchRequest
        .execute(new MultiSearchResponseListener(docsToVerify, originalResponses))
    }

    private final class MultiSearchResponseListener(docsToVerify: NonEmptyList[DocumentWithIndex],
                                                    originalResponses: List[MultiGetItemResponse])
      extends ActionListener[MultiSearchResponse] {

      override def onResponse(response: MultiSearchResponse): Unit = {
        val results = extractResultsFromSearchResponse(response)
        handleResults(results, docsToVerify, originalResponses)
      }

      override def onFailure(e: Exception): Unit = {
        logger.error(s"[${id.show}] Could not verify documents returned by multi get response. Blocking all returned documents", e)
        val results = blockAllDocsReturned(docsToVerify)
        handleResults(results, docsToVerify, originalResponses)
      }

      private def handleResults(results: List[DocumentAccessibility],
                                docsToVerify: NonEmptyList[DocumentWithIndex],
                                originalResponses: List[MultiGetItemResponse]) = {
        val resultsAssignedToDocuments = docsToVerify.toList
          .zip(results)
          .toMap
        val newResponse = prepareNewResponse(originalResponses, resultsAssignedToDocuments)
        underlying.onResponse(newResponse)
      }

      private def blockAllDocsReturned(docsToVerify: NonEmptyList[DocumentWithIndex]) = {
        List.fill(docsToVerify.size)(Inaccessible)
      }

      private def extractResultsFromSearchResponse(multiSearchResponse: MultiSearchResponse) = {
        multiSearchResponse
          .getResponses
          .map(resolveAccessibilityBasedOnSearchResult)
          .toList
      }

      private def resolveAccessibilityBasedOnSearchResult(mSearchItem: MultiSearchResponse.Item): DocumentAccessibility = {
        if (mSearchItem.isFailure) Inaccessible
        else if (mSearchItem.getResponse.getHits.getTotalHits.value == 0L) Inaccessible
        else Accessible
      }

      private def prepareNewResponse(originalResponses: List[MultiGetItemResponse],
                                     verificationResults: Map[DocumentWithIndex, DocumentAccessibility]) = {
        val newResponses = originalResponses
          .map(adjustResponseUsingResolvedAccessibility(verificationResults))
          .toArray
        new MultiGetResponse(newResponses)
      }

      private def adjustResponseUsingResolvedAccessibility(accessibilityPerDocument: Map[DocumentWithIndex, DocumentAccessibility])
                                                          (item: MultiGetItemResponse) = {
        accessibilityPerDocument.get(item.asDocumentWithIndex) match {
          case None | Some(Accessible) => item
          case Some(Inaccessible) =>
            val newResponse = GetApi.doesNotExistResponse(original = item.getResponse)
            new MultiGetItemResponse(newResponse, null)
        }
      }
    }
  }
}