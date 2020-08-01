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

import cats.data.{NonEmptyList, NonEmptySet}
import cats.implicits._
import monix.eval.Task
import org.elasticsearch.action.ActionResponse
import org.elasticsearch.action.search.{SearchRequest, SearchResponse}
import org.elasticsearch.common.bytes.BytesReference
import org.elasticsearch.common.xcontent.support.XContentMapValues
import org.elasticsearch.common.xcontent.{XContentFactory, XContentType}
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.DocumentField.{ADocumentField, NegatedDocumentField}
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.SearchRequestOps._
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.utils.ScalaOps._

class SearchEsRequestContext(actionRequest: SearchRequest,
                             esContext: EsContext,
                             aclContext: AccessControlStaticContext,
                             clusterService: RorClusterService,
                             override val threadPool: ThreadPool)
  extends BaseFilterableEsRequestContext[SearchRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def indicesFrom(request: SearchRequest): Set[IndexName] = {
    request.indices.asSafeSet.flatMap(IndexName.fromString)
  }

  override protected def update(request: SearchRequest,
                                indices: NonEmptyList[IndexName],
                                filter: Option[Filter],
                                fields: Option[NonEmptySet[DocumentField]]): ModificationResult = {
    request
      .applyFilterToQuery(filter)
      .indices(indices.toList.map(_.value.value): _*)
    ModificationResult.UpdateResponse(applyClientFiltering(fields))
  }

  private def applyClientFiltering(fields: Option[NonEmptySet[DocumentField]])
                                  (actionResponse: ActionResponse): Task[ActionResponse] = {
    (actionResponse, fields) match {
      case (response: SearchResponse, Some(definedFields)) =>
        response.getHits.getHits
          .foreach { hit =>
            val (excluding, including) = splitFields(definedFields)
            val responseSource = hit.getSourceAsMap

            if (responseSource != null && responseSource.size() > 0) {
              val filteredSource = XContentMapValues.filter(responseSource, including.toArray, excluding.toArray)
              val newContent = XContentFactory.contentBuilder(XContentType.JSON).map(filteredSource)
              hit.sourceRef(BytesReference.bytes(newContent))
            } else {
              //source not present or empty - nothing to modify
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

}