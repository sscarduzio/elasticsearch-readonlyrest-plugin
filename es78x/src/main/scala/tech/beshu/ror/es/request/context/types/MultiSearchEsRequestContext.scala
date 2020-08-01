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

import cats.data.NonEmptySet
import cats.implicits._
import monix.eval.Task
import org.elasticsearch.action.ActionResponse
import org.elasticsearch.action.search.{MultiSearchRequest, MultiSearchResponse, SearchRequest, SearchResponse}
import org.elasticsearch.common.bytes.BytesReference
import org.elasticsearch.common.xcontent.support.XContentMapValues
import org.elasticsearch.common.xcontent.{XContentFactory, XContentType}
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.FilterableMultiRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiIndexRequestBlockContext.Indices
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.DocumentField.{ADocumentField, NegatedDocumentField}
import tech.beshu.ror.accesscontrol.domain.{DocumentField, Filter, IndexName}
import tech.beshu.ror.accesscontrol.utils.IndicesListOps._
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.SearchRequestOps._
import tech.beshu.ror.es.request.SourceFiltering
import tech.beshu.ror.es.request.context.ModificationResult.{Modified, ShouldBeInterrupted}
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}
import tech.beshu.ror.utils.ScalaOps._

import scala.collection.JavaConverters._

class MultiSearchEsRequestContext(actionRequest: MultiSearchRequest,
                                  esContext: EsContext,
                                  aclContext: AccessControlStaticContext,
                                  clusterService: RorClusterService,
                                  override val threadPool: ThreadPool)
  extends BaseEsRequestContext[FilterableMultiRequestBlockContext](esContext, clusterService)
    with EsRequest[FilterableMultiRequestBlockContext] {

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
    val requests = actionRequest.requests().asScala.toList
    if (requests.size == modifiedPacksOfIndices.size) {
      requests
        .zip(modifiedPacksOfIndices)
        .foreach { case (request, pack) =>
          updateRequest(request, pack, blockContext.filter, blockContext.fields)
        }
      ModificationResult.UpdateResponse(applyClientFiltering(blockContext.fields))
    } else {
      logger.error(s"[${id.show}] Cannot alter MultiSearchRequest request, because origin request contained different number of" +
        s" inner requests, than altered one. This can be security issue. So, it's better for forbid the request")
      ShouldBeInterrupted
    }
  }

  private def applyClientFiltering(fields: Option[NonEmptySet[DocumentField]])
                                  (actionResponse: ActionResponse): Task[ActionResponse] = {
    (actionResponse, fields) match {
      case (response: MultiSearchResponse, Some(definedFields)) =>
        response.getResponses
          .foreach { multiSearchItem =>
            if (multiSearchItem.getResponse != null) {
              multiSearchItem.getResponse.getHits.getHits
                .foreach { hit =>
                  val (excluding, including) = splitFields(definedFields)
                  val responseSource = hit.getSourceAsMap

                  if (responseSource != null && responseSource.size() > 0) {
                    val filteredSource = XContentMapValues.filter(responseSource, including.toArray, excluding.toArray)
                    val newContent = XContentFactory.contentBuilder(XContentType.JSON).map(filteredSource)
                    hit.sourceRef(BytesReference.bytes(newContent))
                  }
                }
            }
          }
        Task.now(response)
      case _ =>
        Task.now(actionResponse)
    }
  }

  private def splitFields(fields: NonEmptySet[DocumentField]) = {
    fields.toNonEmptyList.toList.partitionEither {
      case d: ADocumentField => Right(d.value.value)
      case d: NegatedDocumentField => Left(d.value.value)
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
    val requestIndices = request.indices.asSafeSet.flatMap(IndexName.fromString)
    indicesOrWildcard(requestIndices)
  }

  private def updateRequest(request: SearchRequest,
                            indexPack: Indices,
                            filter: Option[Filter],
                            fields: Option[NonEmptySet[DocumentField]]) = {
    indexPack match {
      case Indices.Found(indices) =>
        updateRequestWithIndices(request, indices)
      case Indices.NotFound =>
        updateRequestWithNonExistingIndex(request)
    }
    request
      .applyFilterToQuery(filter)

    import SourceFiltering._

    val originalFetchSource = request.source().fetchSource()
    val sourceFilteringResult = originalFetchSource.applyNewFields(fields)
    request.source().fetchSource(sourceFilteringResult.modifiedContext)
  }

  private def updateRequestWithIndices(request: SearchRequest, indices: Set[IndexName]) = {
    indices.toList match {
      case Nil => updateRequestWithNonExistingIndex(request)
      case nonEmptyIndicesList => request.indices(nonEmptyIndicesList.map(_.value.value): _*)
    }
  }

  private def updateRequestWithNonExistingIndex(request: SearchRequest): Unit = {
    val originRequestIndices = indicesFrom(request).toList
    val notExistingIndex = originRequestIndices.randomNonexistentIndex()
    request.indices(notExistingIndex.value.value)
  }
}
