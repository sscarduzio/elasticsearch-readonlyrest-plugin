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
import monix.eval.Task
import org.elasticsearch.action.ActionResponse
import org.elasticsearch.action.search.{SearchRequest, SearchResponse}
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.fls.FLS.FieldsUsage
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.SearchHitOps._
import tech.beshu.ror.es.request.SearchRequestOps._
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.queries.QueryFLS.resolveQueryFieldsUsageIn
import tech.beshu.ror.utils.ScalaOps._


class SearchEsRequestContext(actionRequest: SearchRequest,
                             esContext: EsContext,
                             aclContext: AccessControlStaticContext,
                             clusterService: RorClusterService,
                             override val threadPool: ThreadPool)
  extends BaseFilterableEsRequestContext[SearchRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override def fieldsUsage: FieldsUsage = {
    Option(actionRequest.source().scriptFields()) match {
      case Some(scriptFields) if scriptFields.size() > 0 =>
        FieldsUsage.UsingFields.CantExtractFields
      case _ =>
        checkQueryFields()
    }
  }

  def checkQueryFields(): FieldsUsage = {
    Option(actionRequest.source().query())
      .map(resolveQueryFieldsUsageIn)
      .getOrElse(FieldsUsage.NotUsingFields)
  }

  override protected def indicesFrom(request: SearchRequest): Set[IndexName] = {
    request.indices.asSafeSet.flatMap(IndexName.fromString)
  }

  override protected def update(request: SearchRequest,
                                indices: NonEmptyList[IndexName],
                                filter: Option[Filter],
                                fields: Option[Fields]): ModificationResult = {
    request
      .applyFilterToQuery(filter)
      .modifyFieldsInQuery(fields)
      .indices(indices.toList.map(_.value.value): _*)

    ModificationResult.UpdateResponse(filterFieldsFromResponse(fields))
  }

  private def filterFieldsFromResponse(fields: Option[Fields])
                                      (actionResponse: ActionResponse): Task[ActionResponse] = {

    (actionResponse, fields) match {
      case (response: SearchResponse, Some(definedFields)) =>
        response.getHits.getHits
          .foreach { hit =>
            hit
              .modifySourceFieldsUsing(definedFields.restrictions)
              .modifyDocumentFieldsUsing(definedFields.restrictions)
          }
        Task.now(response)
      case _ =>
        Task.now(actionResponse)
    }
  }
}