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
import monix.eval.Task
import org.elasticsearch.action.ActionResponse
import org.elasticsearch.action.get.{GetRequest, GetResponse}
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlList.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.RequestedIndex
import tech.beshu.ror.accesscontrol.domain.DocumentAccessibility.{Accessible, Inaccessible}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, FieldLevelSecurity, Filter}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.RequestSeemsToBeInvalid
import tech.beshu.ror.es.handler.request.context.ModificationResult
import tech.beshu.ror.es.handler.response.DocumentApiOps.GetApi
import tech.beshu.ror.es.handler.response.DocumentApiOps.GetApi.*
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*

class GetEsRequestContext(actionRequest: GetRequest,
                          esContext: EsContext,
                          aclContext: AccessControlStaticContext,
                          clusterService: RorClusterService,
                          override val threadPool: ThreadPool)
  extends BaseFilterableEsRequestContext[GetRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override protected def requestFieldsUsage: RequestFieldsUsage = RequestFieldsUsage.NotUsingFields

  override protected def requestedIndicesFrom(request: GetRequest): Set[RequestedIndex[ClusterIndexName]] = {
    val indexName = RequestedIndex
      .fromString(request.index())
      .getOrElse(throw RequestSeemsToBeInvalid[IndexRequest]("Index name is invalid"))
    Set(indexName)
  }

  override protected def update(request: GetRequest,
                                filteredRequestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]],
                                filter: Option[Filter],
                                fieldLevelSecurity: Option[FieldLevelSecurity]): ModificationResult = {
    val indexName = indices.head
    request.index(indexName.stringify)
    ModificationResult.UpdateResponse(updateFunction(filter, fieldLevelSecurity))
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

  private def filterFieldsFromResponse(fieldLevelSecurity: Option[FieldLevelSecurity],
                                       actionResponse: ActionResponse): ActionResponse = {
    (actionResponse, fieldLevelSecurity) match {
      case (response: GetResponse, Some(definedFieldLevelSecurity)) if response.isExists =>
        response.filterFieldsUsing(definedFieldLevelSecurity.restrictions)
      case _ =>
        actionResponse
    }
  }
}