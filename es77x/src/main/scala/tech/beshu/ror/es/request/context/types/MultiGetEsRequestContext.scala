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
import org.elasticsearch.action.ActionResponse
import org.elasticsearch.action.get.{GetResponse, MultiGetItemResponse, MultiGetRequest, MultiGetResponse}
import org.elasticsearch.action.search.{MultiSearchResponse, SearchRequestBuilder}
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.index.get.GetResult
import org.elasticsearch.index.query.QueryBuilders
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiGetRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiIndexRequestBlockContext.Indices
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.domain.{Filter, IndexName}
import tech.beshu.ror.accesscontrol.utils.IndicesListOps._
import tech.beshu.ror.accesscontrol.{AccessControlStaticContext, domain}
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.ModificationResult.ShouldBeInterrupted
import tech.beshu.ror.es.request.context.types.MultiGetEsRequestContext.MSearchVerificationResult.{Allow, Deny}
import tech.beshu.ror.es.request.context.types.MultiGetEsRequestContext.{DocIndexKey, MSearchVerificationResult}
import tech.beshu.ror.es.request.context.{BaseEsRequestContext, EsRequest, ModificationResult}

import scala.collection.JavaConverters._

class MultiGetEsRequestContext(actionRequest: MultiGetRequest,
                               esContext: EsContext,
                               aclContext: AccessControlStaticContext,
                               clusterService: RorClusterService,
                               nodeClient: NodeClient,
                               override val threadPool: ThreadPool)
  extends BaseEsRequestContext[MultiGetRequestBlockContext](esContext, clusterService)
    with EsRequest[MultiGetRequestBlockContext] {

  override lazy val initialBlockContext: MultiGetRequestBlockContext = MultiGetRequestBlockContext(
    this,
    UserMetadata.from(this),
    Set.empty,
    Set.empty,
    indexPacksFrom(actionRequest),
    None
  )

  override protected def modifyRequest(blockContext: MultiGetRequestBlockContext): ModificationResult = {
    val modifiedPacksOfIndices = blockContext.indexPacks
    val items = actionRequest.getItems.asScala.toList
    if (items.size == modifiedPacksOfIndices.size) {
      items
        .zip(modifiedPacksOfIndices)
        .foreach { case (item, pack) =>
          updateItem(item, pack)
        }
      ModificationResult.UpdateResponse(applyFilterToResponse(blockContext.filter))
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

  private def updateItem(item: MultiGetRequest.Item, indexPack: Indices): Unit = {
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

  private def applyFilterToResponse(filter: Option[Filter])
                                   (actionResponse: ActionResponse): ActionResponse = {

    def requiresSearchBasedVerification(item: MultiGetItemResponse) = {
      !item.isFailed && item.getResponse.isExists
    }

    def executeMSearch(definedFilter: Filter,
                       docsRequiringVerification: List[DocIndexKey]) = {
      docsRequiringVerification
        .map(createSearchRequestFrom(definedFilter))
        .foldLeft(nodeClient.prepareMultiSearch())(_ add _)
        .get()
    }

    def createSearchRequestFrom(definedFilter: Filter)
                               (docWithIndex: DocIndexKey): SearchRequestBuilder = {
      val filterQuery = QueryBuilders.wrapperQuery(definedFilter.value.value)
      val composedQuery = QueryBuilders
        .boolQuery()
        .filter(QueryBuilders.constantScoreQuery(filterQuery))
        .filter(QueryBuilders.idsQuery().addIds(docWithIndex.docId))

      nodeClient
        .prepareSearch(docWithIndex.index)
        .setQuery(composedQuery)
    }

    def verifyDocIsAllowed(mSearchItem: MultiSearchResponse.Item): MSearchVerificationResult = {
      if (mSearchItem.isFailure) Deny
      else if (mSearchItem.getResponse.getHits.getTotalHits.value < 1) Deny
      else Allow
    }

    def docIndexKeyFrom(itemResponse: MultiGetItemResponse) =
      DocIndexKey(itemResponse.getIndex, itemResponse.getId)

    def createNewResponseBasedOn(resultPerDoc: Map[DocIndexKey, MSearchVerificationResult])
                                (item: MultiGetItemResponse) = {
      resultPerDoc(docIndexKeyFrom(item)) match {
        case Allow => item
        case Deny =>
          val response = item.getResponse
          val exists = false
          val source = null
          val result = new GetResult(
            response.getIndex,
            response.getType,
            response.getId,
            response.getSeqNo,
            response.getPrimaryTerm,
            response.getVersion,
            exists,
            source,
            java.util.Collections.emptyMap(),
            java.util.Collections.emptyMap())
          val newResponse = new GetResponse(result)
          new MultiGetItemResponse(newResponse, null)
      }
    }

    actionResponse match {
      case response: MultiGetResponse =>
        filter match {
          case Some(definedFilter) =>
            val responses = response.getResponses.toList
            val (needToVerifyItems, noNeedToVerifyItems) = responses.partition(requiresSearchBasedVerification)

            if (needToVerifyItems.nonEmpty) {
              val needToVerifyUniqueDocs = needToVerifyItems.map(docIndexKeyFrom).distinct
              val noNeedToVerifyUniqueDocs = noNeedToVerifyItems.map(docIndexKeyFrom).distinct

              val mSearchResponse = executeMSearch(definedFilter, needToVerifyUniqueDocs)
              val verificationResults = mSearchResponse.getResponses.map(verifyDocIsAllowed)
              val resultsForVerifiedDocs = needToVerifyUniqueDocs
                .zip(verificationResults)
                .toMap

              val alwaysAllowResults = (noNeedToVerifyUniqueDocs zip List.fill(noNeedToVerifyUniqueDocs.size)(Allow)).toMap
              val allResults = resultsForVerifiedDocs ++ alwaysAllowResults

              val newResponses = responses
                .map(createNewResponseBasedOn(allResults))
              new MultiGetResponse(newResponses.toArray)
            } else {
              response
            }
          case None => response
        }
      case other => other
    }
  }
}

object MultiGetEsRequestContext {
  final case class DocIndexKey(index: String, docId: String)

  sealed trait MSearchVerificationResult
  object MSearchVerificationResult {
    case object Allow extends MSearchVerificationResult
    case object Deny extends MSearchVerificationResult
  }
}
