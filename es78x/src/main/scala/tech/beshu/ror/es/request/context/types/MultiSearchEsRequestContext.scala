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

import cats.implicits._
import monix.eval.Task
import org.elasticsearch.action.ActionResponse
import org.elasticsearch.action.search.{MultiSearchRequest, MultiSearchResponse, SearchRequest}
import org.elasticsearch.common.bytes.BytesReference
import org.elasticsearch.common.document.{DocumentField => EDF}
import org.elasticsearch.common.xcontent.support.XContentMapValues
import org.elasticsearch.common.xcontent.{XContentFactory, XContentType}
import org.elasticsearch.index.query.{MatchQueryBuilder, QueryBuilders, TermQueryBuilder}
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.FilterableMultiRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiIndexRequestBlockContext.Indices
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.FieldsRestrictions.AccessMode
import tech.beshu.ror.accesscontrol.domain.{FieldsRestrictions, Filter, IndexName}
import tech.beshu.ror.accesscontrol.utils.IndicesListOps._
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.SearchRequestOps._
import tech.beshu.ror.es.request.context.ModificationResult.{Modified, ShouldBeInterrupted}
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}
import tech.beshu.ror.fls.FieldsPolicy
import tech.beshu.ror.utils.ScalaOps._

import scala.collection.JavaConverters._

class MultiSearchEsRequestContext(actionRequest: MultiSearchRequest,
                                  esContext: EsContext,
                                  aclContext: AccessControlStaticContext,
                                  clusterService: RorClusterService,
                                  override val threadPool: ThreadPool)
  extends BaseEsRequestContext[FilterableMultiRequestBlockContext](esContext, clusterService)
    with EsRequest[FilterableMultiRequestBlockContext] {

  override val requiresContextHeader: Boolean = false

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
          updateRequest(request, pack, blockContext.filter, blockContext.fieldsRestrictions)
        }
      ModificationResult.UpdateResponse(applyClientFiltering(blockContext.fieldsRestrictions))
    } else {
      logger.error(s"[${id.show}] Cannot alter MultiSearchRequest request, because origin request contained different number of" +
        s" inner requests, than altered one. This can be security issue. So, it's better for forbid the request")
      ShouldBeInterrupted
    }
  }

  private def applyClientFiltering(fields: Option[FieldsRestrictions])
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

                  //handle fields
                  val (metdataFields, nonMetadaDocumentFields) = splitFieldsByMetadata(hit.getFields.asScala.toMap)

                  val policy = new FieldsPolicy(definedFields)

                  val filteredFields = nonMetadaDocumentFields.filter {
                    case (key, _) => policy.canKeep(key)
                  }

                  val allNewFields = (metdataFields ++ filteredFields).asJava

                  hit.fields(allNewFields)
                }
            }
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

  private def splitFields(fields: FieldsRestrictions) = fields.mode match {
    case AccessMode.Whitelist => (List.empty, fields.fields.map(_.value.value).toList)
    case AccessMode.Blacklist => (fields.fields.map(_.value.value).toList, List.empty)
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
                            fields: Option[FieldsRestrictions]) = {
    indexPack match {
      case Indices.Found(indices) =>
        updateRequestWithIndices(request, indices)
      case Indices.NotFound =>
        updateRequestWithNonExistingIndex(request)
    }
    request
      .applyFilterToQuery(filter)

    applyFieldsToQuery(request, fields)
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
