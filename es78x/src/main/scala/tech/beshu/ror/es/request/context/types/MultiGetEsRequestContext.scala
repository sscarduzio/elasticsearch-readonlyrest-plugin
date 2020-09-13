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
import monix.eval.Task
import org.elasticsearch.action.ActionResponse
import org.elasticsearch.action.get.{GetResponse, MultiGetItemResponse, MultiGetRequest, MultiGetResponse}
import org.elasticsearch.index.get.GetResult
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.FilterableMultiRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiIndexRequestBlockContext.Indices
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.DocumentAccessibility.{Accessible, Inaccessible}
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.fls.FLS
import tech.beshu.ror.accesscontrol.utils.IndicesListOps._
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.DocumentApiOps.GetApi
import tech.beshu.ror.es.request.DocumentApiOps.GetApi._
import tech.beshu.ror.es.request.DocumentApiOps.MultiGetApi._
import tech.beshu.ror.es.request.FieldsFiltering
import tech.beshu.ror.es.request.context.ModificationResult.ShouldBeInterrupted
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}

import scala.collection.JavaConverters._

class MultiGetEsRequestContext(actionRequest: MultiGetRequest,
                               esContext: EsContext,
                               clusterService: RorClusterService,
                               override val threadPool: ThreadPool)
  extends BaseEsRequestContext[FilterableMultiRequestBlockContext](esContext, clusterService)
    with EsRequest[FilterableMultiRequestBlockContext] {

  override def fieldsUsage: FLS.RequestFieldsUsage = FLS.RequestFieldsUsage.NotUsingFields

  override lazy val initialBlockContext: FilterableMultiRequestBlockContext = FilterableMultiRequestBlockContext(
    this,
    UserMetadata.from(this),
    Set.empty,
    Set.empty,
    indexPacksFrom(actionRequest),
    None,
    None
  )

  override protected def modifyRequest(blockContext: FilterableMultiRequestBlockContext): ModificationResult = {
    val modifiedPacksOfIndices = blockContext.indexPacks
    val items = actionRequest.getItems.asScala.toList
    if (items.size == modifiedPacksOfIndices.size) {
      items
        .zip(modifiedPacksOfIndices)
        .foreach { case (item, pack) =>
          updateItem(item, pack, blockContext.fields)
        }
      val function = filterResponse(blockContext.filter) _
      val updateFunction =
        function
          .andThen(_.map(filterFieldsFromResponse(blockContext.fields)))

      ModificationResult.UpdateResponse(updateFunction)
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

  private def updateItem(item: MultiGetRequest.Item,
                         indexPack: Indices,
                         fields: Option[Fields]): Unit = {
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

  private def filterResponse(filter: Option[Filter])
                            (actionResponse: ActionResponse): Task[ActionResponse] = {
    (actionResponse, filter) match {
      case (response: MultiGetResponse, Some(definedFilter)) =>
        applyFilter(response, definedFilter)
      case _ =>
        Task.now(actionResponse)
    }
  }

  private def filterFieldsFromResponse(fields: Option[Fields])
                                      (actionResponse: ActionResponse): ActionResponse = {
    (actionResponse, fields) match {
      case (response: MultiGetResponse, Some(definedFields)) =>
        val newResponses = response.getResponses
          .map {
            case multiGetItem if !multiGetItem.isFailed =>
              val getResponse = multiGetItem.getResponse
              val newSource = getResponse.provideNewSourceUsing(definedFields.restrictions)
              val newFields = FieldsFiltering.provideFilteredDocumentFields(getResponse.getFields.asScala.toMap, definedFields.restrictions)

              val result = new GetResult(
                getResponse.getIndex,
                getResponse.getType,
                getResponse.getId,
                getResponse.getSeqNo,
                getResponse.getPrimaryTerm,
                getResponse.getVersion,
                true,
                newSource,
                newFields.documentFields.asJava,
                newFields.metadataFields.asJava)
              new MultiGetItemResponse(new GetResponse(result), null)
            case other => other
          }
        new MultiGetResponse(newResponses)
      case _ =>
        actionResponse
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
}