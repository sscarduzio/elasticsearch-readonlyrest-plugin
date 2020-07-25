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

import java.nio.ByteBuffer

import cats.data.{NonEmptyList, NonEmptySet}
import com.google.gson.Gson
import monix.eval.Task
import org.elasticsearch.action.ActionResponse
import org.elasticsearch.action.search.{SearchRequest, SearchResponse}
import org.elasticsearch.common.bytes.BytesReference
import org.elasticsearch.common.xcontent.json.JsonXContent
import org.elasticsearch.common.xcontent.support.XContentMapValues
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.SearchRequestOps._
import tech.beshu.ror.es.request.SourceFiltering
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

    import SourceFiltering._
    val originalFetchSource = request.source().fetchSource()
    val sourceFilteringResult = originalFetchSource.applyNewFields(fields)
      request.source().fetchSource(sourceFilteringResult.modifiedContext)

    sourceFilteringResult match {
      case _: SourceFilteringResult.Applied =>
        ModificationResult.Modified
      case result: SourceFilteringResult.ClientFilteringNotApplied =>
        ModificationResult.UpdateResponse(applyClientFiltering(result.ignoredClientFiltering))
    }
  }

  private def applyClientFiltering(clientFiltering: Array[String])
                                  (actionResponse: ActionResponse): Task[ActionResponse] = {
    actionResponse match {
      case response: SearchResponse =>
        response.getHits.getHits
          .foreach { hit =>
            val map = hit.getSourceAsMap
            val map1 = XContentMapValues.filter(map, clientFiltering, Array.empty[String])
            val str = new Gson().toJson(map1)
            JsonXContent.jsonXContent
            val bytes = str.map(_.toByte).toArray
            val newSource = BytesReference.fromByteBuffer(ByteBuffer.wrap(bytes))
            hit.sourceRef(newSource)
          }
        Task.now(response)

//        NonEmptyList.fromList(documents.toList) match {
//          case Some(documentsToUpdate) =>
//            clusterService
//              .provideNewSourcesFor(documentsToUpdate, clientFiltering, id)
//              .map { newSources =>
//                response.getHits.getHits
//                  .foreach { hit =>
//                    val document = DocumentWithIndex(IndexName.fromUnsafeString(hit.getIndex), DocumentId(hit.getId))
//                    val newSource = newSources(document)
//                    hit.sourceRef(BytesReference.fromByteBuffer(ByteBuffer.wrap(newSource)))
//                  }
//                response
//              }
//          case None =>
//            Task.now(actionResponse)
//        }
      case _ => Task.now(actionResponse)
    }
  }
}