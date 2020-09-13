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
import org.elasticsearch.action.get.{GetRequest, GetResponse}
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.index.get.GetResult
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.DocumentAccessibility.{Accessible, Inaccessible}
import tech.beshu.ror.accesscontrol.domain.{Fields, Filter, IndexName}
import tech.beshu.ror.accesscontrol.fls.FLS
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.DocumentApiOps.GetApi
import tech.beshu.ror.es.request.DocumentApiOps.GetApi._
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.es.request.{FieldsFiltering, RequestSeemsToBeInvalid}

import scala.collection.JavaConverters._

class GetEsRequestContext(actionRequest: GetRequest,
                          esContext: EsContext,
                          aclContext: AccessControlStaticContext,
                          clusterService: RorClusterService,
                          override val threadPool: ThreadPool)
  extends BaseFilterableEsRequestContext[GetRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override def fieldsUsage: FLS.RequestFieldsUsage = FLS.RequestFieldsUsage.NotUsingFields

  override protected def indicesFrom(request: GetRequest): Set[IndexName] = {
    val indexName = IndexName
      .fromString(request.index())
      .getOrElse {
        throw RequestSeemsToBeInvalid[IndexRequest]("Index name is invalid")
      }
    Set(indexName)
  }

  override protected def update(request: GetRequest,
                                indices: NonEmptyList[IndexName],
                                filter: Option[Filter],
                                fields: Option[Fields]): ModificationResult = {
    val indexName = indices.head
    request.index(indexName.value.value)
    val function = filterResponse(filter) _
    val updateFunction =
      function
        .andThen(_.map(filterFieldsFromResponse(fields)))
    ModificationResult.UpdateResponse(updateFunction)
  }

  private def filterResponse(filter: Option[Filter])
                            (actionResponse: ActionResponse): Task[ActionResponse] = {
    (actionResponse, filter) match {
      case (response: GetResponse, Some(definedFilter)) if response.isExists =>
        handleExistingResponse(response, definedFilter)
      case _ => Task.now(actionResponse)
    }
  }

  private def handleExistingResponse(response: GetResponse,
                                     definedFilter: Filter) = {
    clusterService.verifyDocumentAccessibility(response.asDocumentWithIndex, definedFilter, id)
      .map {
        case Inaccessible => GetApi.doesNotExistResponse(response)
        case Accessible => response
      }
  }

  private def filterFieldsFromResponse(fields: Option[Fields])
                                      (actionResponse: ActionResponse): ActionResponse = {
    (actionResponse, fields) match {
      case (response: GetResponse, Some(definedFields)) if response.isExists =>
        val newSource = response.provideNewSourceUsing(definedFields.restrictions)
        val newFields = FieldsFiltering.provideFilteredDocumentFields(response.getFields.asScala.toMap, definedFields.restrictions)

        val newResult = new GetResult(
          response.getIndex,
          response.getType,
          response.getId,
          response.getSeqNo,
          response.getPrimaryTerm,
          response.getVersion,
          true,
          newSource,
          newFields.documentFields.asJava,
          newFields.metadataFields.asJava)
        new GetResponse(newResult)
      case _ =>
        actionResponse
    }
  }
}