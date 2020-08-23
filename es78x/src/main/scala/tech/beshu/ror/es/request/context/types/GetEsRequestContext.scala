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
import org.elasticsearch.action.get.{GetRequest, GetResponse}
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.common.bytes.BytesReference
import org.elasticsearch.common.document.{DocumentField => EDF}
import org.elasticsearch.common.xcontent.support.XContentMapValues
import org.elasticsearch.common.xcontent.{XContentFactory, XContentType}
import org.elasticsearch.index.get.GetResult
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.DocumentAccessibility.{Accessible, Inaccessible}
import tech.beshu.ror.accesscontrol.domain.FieldsRestrictions.AccessMode
import tech.beshu.ror.accesscontrol.domain.{FieldsRestrictions, Filter, IndexName}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.DocumentApiOps.GetApi
import tech.beshu.ror.es.request.DocumentApiOps.GetApi._
import tech.beshu.ror.es.request.RequestSeemsToBeInvalid
import tech.beshu.ror.es.request.context.ModificationResult

class GetEsRequestContext(actionRequest: GetRequest,
                          esContext: EsContext,
                          aclContext: AccessControlStaticContext,
                          clusterService: RorClusterService,
                          override val threadPool: ThreadPool)
  extends BaseFilterableEsRequestContext[GetRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override val requiresContextHeader: Boolean = false

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
                                fields: Option[FieldsRestrictions]): ModificationResult = {
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

  private def filterFieldsFromResponse(fields: Option[FieldsRestrictions])
                                      (actionResponse: ActionResponse): ActionResponse = {
    (actionResponse, fields) match {
      case (response: GetResponse, Some(definedFields)) if response.isExists && !response.isSourceEmpty =>
        val (excluding, including) = splitFields(definedFields)
        val filteredSource = XContentMapValues.filter(response.getSource, including.toArray, excluding.toArray)
        val newContent = XContentFactory.contentBuilder(XContentType.JSON).map(filteredSource)
        import scala.collection.JavaConverters._

        val (metdataFields, nonMetadaDocumentFields) = splitFieldsByMetadata(response.getFields.asScala.toMap)

        val result = new GetResult(
          response.getIndex,
          response.getType,
          response.getId,
          response.getSeqNo,
          response.getPrimaryTerm,
          response.getVersion,
          true,
          BytesReference.bytes(newContent),
          nonMetadaDocumentFields.asJava,
          metdataFields.asJava)
        new GetResponse(result)
      case _ =>
        actionResponse
    }
  }

  def splitFieldsByMetadata(fields: Map[String, EDF]): (Map[String, EDF], Map[String, EDF]) = {
    fields.partition {
      case t if t._2.isMetadataField => true
      case _ => false
    }
  }

  private def splitFields(fields: FieldsRestrictions) = fields.mode match {
    case AccessMode.Whitelist => (List.empty, fields.fields.map(_.value.value).toList)
    case AccessMode.Blacklist => (fields.fields.map(_.value.value).toList, List.empty)
  }

  private def handleExistingResponse(response: GetResponse,
                                     definedFilter: Filter) = {
    clusterService.verifyDocumentAccessibility(response.asDocumentWithIndex, definedFilter, id)
      .map {
        case Inaccessible => GetApi.doesNotExistResponse(response)
        case Accessible => response
      }
  }
}