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
import cats.implicits.*
import monix.eval.Task
import org.elasticsearch.action.ActionResponse
import org.elasticsearch.action.get.{MultiGetItemResponse, MultiGetRequest, MultiGetResponse}
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.{FilterableMultiRequestBlockContext, RequestedIndex}
import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiIndexRequestBlockContext.Indices
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.DocumentAccessibility.{Accessible, Inaccessible}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage
import tech.beshu.ror.accesscontrol.utils.IndicesListOps.*
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.request.context.ModificationResult.ShouldBeInterrupted
import tech.beshu.ror.es.handler.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}
import tech.beshu.ror.es.handler.response.DocumentApiOps.GetApi
import tech.beshu.ror.es.handler.response.DocumentApiOps.GetApi.*
import tech.beshu.ror.es.handler.response.DocumentApiOps.MultiGetApi.*
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*

import scala.jdk.CollectionConverters.*

class MultiGetEsRequestContext(actionRequest: MultiGetRequest,
                               esContext: EsContext,
                               clusterService: RorClusterService,
                               override val threadPool: ThreadPool)
  extends BaseEsRequestContext[FilterableMultiRequestBlockContext](esContext, clusterService)
    with EsRequest[FilterableMultiRequestBlockContext] {

  private val requestFieldsUsage: RequestFieldsUsage = RequestFieldsUsage.NotUsingFields

  override lazy val initialBlockContext: FilterableMultiRequestBlockContext = FilterableMultiRequestBlockContext(
    requestContext = this,
    userMetadata = UserMetadata.from(this),
    responseHeaders = Set.empty,
    responseTransformations = List.empty,
    indexPacks = indexPacksFrom(actionRequest),
    filter = None,
    fieldLevelSecurity = None,
    requestFieldsUsage = requestFieldsUsage
  )

  override lazy val indexAttributes: Set[IndexAttribute] = {
    // It may be a problem in some cases. We get all possible index attributes and we put them to one bag.
    actionRequest
      .getItems.asScala
      .flatMap(indexAttributesFrom)
      .toCovariantSet
  }

  override protected def modifyRequest(blockContext: FilterableMultiRequestBlockContext): ModificationResult = {
    val modifiedPacksOfIndices = blockContext.indexPacks
    val items = actionRequest.getItems.asScala.toList
    if (items.size == modifiedPacksOfIndices.size) {
      items
        .zip(modifiedPacksOfIndices)
        .foreach { case (item, pack) =>
          updateItem(item, pack)
        }
      ModificationResult.UpdateResponse(updateFunction(blockContext.filter, blockContext.fieldLevelSecurity))
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

  private def indicesFrom(item: MultiGetRequest.Item): Set[ClusterIndexName] = {
    item
      .indices.asSafeSet
      .flatMap(RequestedIndex.fromString)
      .orWildcardWhenEmpty
  }

  private def updateItem(item: MultiGetRequest.Item,
                         indexPack: Indices): Unit = {
    indexPack match {
      case Indices.Found(indices) =>
        updateItemWithIndices(item, indices)
      case Indices.NotFound =>
        updateItemWithNonExistingIndex(item)
    }
  }

  private def updateItemWithIndices(item: MultiGetRequest.Item, indices: Set[RequestedIndex]) = {
    indices.toList match {
      case Nil => updateItemWithNonExistingIndex(item)
      case index :: rest =>
        if (rest.nonEmpty) {
          logger.warn(s"[${id.show}] Filtered result contains more than one index. First was taken. The whole set of indices [${indices.show}]")
        }
        item.index(index.stringify)
    }
  }

  private def updateItemWithNonExistingIndex(item: MultiGetRequest.Item): Unit = {
    val originRequestIndices = indicesFrom(item).toList
    val notExistingIndex = originRequestIndices.map(_.name).randomNonexistentIndex()
    item.index(notExistingIndex.stringify)
  }

  private def updateFunction(filter: Option[Filter],
                             fieldLevelSecurity: Option[FieldLevelSecurity])
                            (actionResponse: ActionResponse): Task[ActionResponse] = {
    filterResponse(filter, actionResponse)
      .map(response => filterFieldsFromResponse(fieldLevelSecurity, response))
  }

  private def filterResponse(filter: Option[Filter],
                             actionResponse: ActionResponse): Task[ActionResponse] = {
    (actionResponse, filter) match {
      case (response: MultiGetResponse, Some(definedFilter)) =>
        applyFilter(response, definedFilter)
      case _ =>
        Task.now(actionResponse)
    }
  }

  private def applyFilter(response: MultiGetResponse,
                          definedFilter: Filter): Task[ActionResponse] = {
    val originalResponses = response.getResponses.toList

    NonEmptyList.fromList(identifyDocumentsToVerifyUsing(originalResponses)) match {
      case Some(existingDocumentsToVerify) =>
        clusterService.verifyDocumentsAccessibilities(existingDocumentsToVerify, definedFilter, id)
          .map { results =>
            prepareNewResponse(originalResponses, results)
          }
      case None =>
        Task.now(response)
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

  private def filterFieldsFromResponse(fieldLevelSecurity: Option[FieldLevelSecurity],
                                       actionResponse: ActionResponse): ActionResponse = {
    (actionResponse, fieldLevelSecurity) match {
      case (response: MultiGetResponse, Some(definedFieldLevelSecurity)) =>
        val newResponses = response.getResponses
          .map {
            case multiGetItem if !multiGetItem.isFailed =>
              val newGetResponse = multiGetItem.getResponse.filterFieldsUsing(definedFieldLevelSecurity.restrictions)
              new MultiGetItemResponse(newGetResponse, null)
            case other => other
          }
        new MultiGetResponse(newResponses)
      case _ =>
        actionResponse
    }
  }
}