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
import org.elasticsearch.action.get.{MultiGetItemResponse, MultiGetRequest, MultiGetResponse}
import org.elasticsearch.action.search.{MultiSearchRequestBuilder, MultiSearchResponse}
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiGetRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiIndexRequestBlockContext.Indices
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.{Filter, IndexName}
import tech.beshu.ror.accesscontrol.utils.IndicesListOps._
import tech.beshu.ror.accesscontrol.{AccessControlStaticContext, domain}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.DocumentApiOps.DocumentAccessibility.{Accessible, Inaccessible}
import tech.beshu.ror.es.request.DocumentApiOps.MultiGetApi._
import tech.beshu.ror.es.request.DocumentApiOps.{DocumentAccessibility, DocumentWithIndex, GetApi, createSearchRequest}
import tech.beshu.ror.es.request.context.ModificationResult.ShouldBeInterrupted
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

class MultiGetEsRequestContext(actionRequest: MultiGetRequest,
                               esContext: EsContext,
                               aclContext: AccessControlStaticContext,
                               clusterService: RorClusterService,
                               nodeClient: NodeClient,
                               override val threadPool: ThreadPool)
  extends BaseEsRequestContext[MultiGetRequestBlockContext](esContext, clusterService)
    with EsRequest[MultiGetRequestBlockContext] {

  override lazy val initialBlockContext: MultiGetRequestBlockContext = MultiGetRequestBlockContext(
    this,
    UserMetadata.from(this),
    Set.empty,
    Set.empty,
    indexPacksFrom(actionRequest),
    None
  )

  override protected def modifyRequest(blockContext: MultiGetRequestBlockContext): ModificationResult = {
    val modifiedPacksOfIndices = blockContext.indexPacks
    val items = actionRequest.getItems.asScala.toList
    if (items.size == modifiedPacksOfIndices.size) {
      items
        .zip(modifiedPacksOfIndices)
        .foreach { case (item, pack) =>
          updateItem(item, pack)
        }
      ModificationResult.UpdateResponse(applyFilterToResponse(blockContext.filter))
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

  private def applyFilterToResponse(filter: Option[Filter])
                                   (actionResponse: ActionResponse): ActionResponse = {
    (actionResponse, filter) match {
      case (response: MultiGetResponse, Some(definedFilter)) =>
        val originalResponses = response.getResponses.toList

        NonEmptyList.fromList(identifyDocumentsToVerifyUsing(originalResponses)) match {
          case Some(existingDocumentsToVerify) =>
            val verificationResult = verifyDocumentsAccessibility(definedFilter, existingDocumentsToVerify)
            prepareNewResponse(originalResponses, verificationResult)
          case None =>
            response
        }
      case (other, _) => other
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

  private def verifyDocumentsAccessibility(definedFilter: Filter,
                                           docsToVerify: NonEmptyList[DocumentWithIndex]): Map[DocumentWithIndex, DocumentAccessibility] = {
    val mSearchRequest = createMSearchRequest(definedFilter, docsToVerify)
    val results = executeMultiSearch(docsToVerify, mSearchRequest)
    docsToVerify.toList
      .zip(results)
      .toMap
  }

  private def createMSearchRequest(definedFilter: Filter,
                                   docsToVerify: NonEmptyList[DocumentWithIndex]) = {
    docsToVerify
      .map(createSearchRequest(nodeClient, definedFilter))
      .foldLeft(nodeClient.prepareMultiSearch())(_ add _)
  }

  private def executeMultiSearch(docsToVerify: NonEmptyList[DocumentWithIndex],
                                 mSearchRequest: MultiSearchRequestBuilder) = {
    Try(mSearchRequest.get()) match {
      case Failure(exception) =>
        logger.error(s"Could not verify documents returned by multi get response. Blocking all returned documents", exception)
        blockAllDocsReturned(docsToVerify)
      case Success(multiSearchResponse) =>
        handleMultiSearchResponse(multiSearchResponse)
    }
  }

  private def blockAllDocsReturned(docsToVerify: NonEmptyList[DocumentWithIndex]) = {
    List.fill(docsToVerify.size)(Inaccessible)
  }

  private def handleMultiSearchResponse(multiSearchResponse: MultiSearchResponse) = {
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