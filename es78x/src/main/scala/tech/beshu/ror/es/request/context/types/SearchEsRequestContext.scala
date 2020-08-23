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
import org.elasticsearch.action.search.{SearchRequest, SearchResponse}
import org.elasticsearch.common.document.{DocumentField => EDF}
import org.elasticsearch.common.bytes.BytesReference
import org.elasticsearch.common.xcontent.support.XContentMapValues
import org.elasticsearch.common.xcontent.{XContentFactory, XContentType}
import org.elasticsearch.index.query.{AbstractQueryBuilder, MatchQueryBuilder, MultiTermQueryBuilder, QueryBuilders, SpanQueryBuilder, TermQueryBuilder}
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.FieldsRestrictions.AccessMode
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.SearchRequestOps._
import tech.beshu.ror.es.request.context.ModificationResult
import tech.beshu.ror.fls.FieldsPolicy
import tech.beshu.ror.utils.ScalaOps._

import scala.collection.JavaConverters._


class SearchEsRequestContext(actionRequest: SearchRequest,
                             esContext: EsContext,
                             aclContext: AccessControlStaticContext,
                             clusterService: RorClusterService,
                             override val threadPool: ThreadPool)
  extends BaseFilterableEsRequestContext[SearchRequest](actionRequest, esContext, aclContext, clusterService, threadPool) {

  override val requiresContextHeader: Boolean = {
    actionRequest.source().query() match {
      case _: TermQueryBuilder => false
      case _ => false
    }
  }

  override protected def indicesFrom(request: SearchRequest): Set[IndexName] = {
    request.indices.asSafeSet.flatMap(IndexName.fromString)
  }

  override protected def update(request: SearchRequest,
                                indices: NonEmptyList[IndexName],
                                filter: Option[Filter],
                                fields: Option[FieldsRestrictions]): ModificationResult = {
    request
      .applyFilterToQuery(filter)
      .indices(indices.toList.map(_.value.value): _*)

    applyFieldsToQuery(request, fields)

    ModificationResult.UpdateResponse(applyClientFiltering(fields))
  }

  private def applyFieldsToQuery(request: SearchRequest,
                                 fieldsRestrictions: Option[FieldsRestrictions]): SearchRequest = {

    fieldsRestrictions match {
      case Some(definedFields) =>
        request.source().query() match {
          case builder: TermQueryBuilder =>
            val fieldsPolicy = new FieldsPolicy(definedFields)
            if (fieldsPolicy.canKeep(builder.fieldName())) {
              request
            } else {
              val someRandomShit = "ROR123123123123123"
              val newQuery = QueryBuilders.termQuery(someRandomShit, builder.value())
              request.source().query(newQuery)
              request
            }
          case builder: MatchQueryBuilder =>
            val fieldsPolicy = new FieldsPolicy(definedFields)
            if (fieldsPolicy.canKeep(builder.fieldName())) {
              request
            } else {
              val someRandomShit = "ROR123123123123123"
              val newQuery = QueryBuilders.matchQuery(someRandomShit, builder.value())
              request.source().query(newQuery)
              request
            }

          case _ => request
        }

      case None =>
        request
    }
  }

  private def applyClientFiltering(fields: Option[FieldsRestrictions])
                                  (actionResponse: ActionResponse): Task[ActionResponse] = {
    (actionResponse, fields) match {
      case (response: SearchResponse, Some(definedFields)) =>
        println(response.getHits.getHits.length)
        response.getHits.getHits
          .foreach { hit =>
            val (excluding, including) = splitFields(definedFields)
            val responseSource = hit.getSourceAsMap


            //handle _source
            if (responseSource != null && responseSource.size() > 0) {
              val filteredSource = XContentMapValues.filter(responseSource, including.toArray, excluding.toArray)
              val newContent = XContentFactory.contentBuilder(XContentType.JSON).map(filteredSource)
              hit.sourceRef(BytesReference.bytes(newContent))
            } else {
              //source not present or empty - nothing to modify
            }

              //handle fields
            val (metdataFields, nonMetadaDocumentFields) = splitFieldsByMetadata(hit.getFields.asScala.toMap)

            val policy = new FieldsPolicy(definedFields)

            val filteredFields = nonMetadaDocumentFields.filter {
              case (key, _) => policy.canKeep(key)
            }

            val allNewFields = (metdataFields ++ filteredFields).asJava

            hit.fields(allNewFields)

          }
        Task.now(response)
      case _ =>
        Task.now(actionResponse)
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

}