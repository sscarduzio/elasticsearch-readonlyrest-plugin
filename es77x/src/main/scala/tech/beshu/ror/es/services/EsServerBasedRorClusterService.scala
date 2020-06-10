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
package tech.beshu.ror.es.services

import cats.data.NonEmptyList
import cats.implicits._
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.ActionListener
import org.elasticsearch.action.search.{MultiSearchResponse, SearchRequestBuilder, SearchResponse}
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.index.query.QueryBuilders
import tech.beshu.ror.accesscontrol.domain.DocumentAccessibility.{Accessible, Inaccessible}
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.RorClusterService._
import tech.beshu.ror.es.services.EsServerBasedRorClusterService.{MultiSearchResponseListener, SearchResponseListener}
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.collection.JavaConverters._
import scala.concurrent.Promise

class EsServerBasedRorClusterService(clusterService: ClusterService,
                                     nodeClient: NodeClient)
  extends RorClusterService
    with Logging {

  override def indexOrAliasUuids(indexOrAlias: IndexOrAlias): Set[IndexUuid] = {
    val lookup = clusterService.state.metaData.getAliasAndIndexLookup
    lookup.get(indexOrAlias.value.value).getIndices.asScala.map(_.getIndexUUID).toSet
  }

  override def allIndicesAndAliases: Map[IndexName, Set[AliasName]] = {
    val indices = clusterService.state.metaData.getIndices
    indices
      .keysIt().asScala
      .flatMap { index =>
        val indexMetaData = indices.get(index)
        IndexName
          .fromString(indexMetaData.getIndex.getName)
          .map { indexName =>
            val aliases = indexMetaData.getAliases.keysIt.asScala.toSet.flatMap(IndexName.fromString)
            (indexName, aliases)
          }
      }
      .toMap
  }

  override def allTemplates: Set[Template] = {
    val templates = clusterService.state.getMetaData.templates()
    templates
      .keysIt().asScala
      .flatMap { templateNameString =>
        val templateMetaData = templates.get(templateNameString)
        for {
          templateName <- NonEmptyString.unapply(templateNameString).map(TemplateName.apply)
          indexPatterns <- UniqueNonEmptyList.fromList(
            templateMetaData.patterns().asScala.flatMap(IndexName.fromString).toList
          )
        } yield Template(templateName, indexPatterns)
      }
      .toSet
  }

  override def verifyDocumentAccessibility(document: Document,
                                           filter: Filter,
                                           id: RequestContext.Id): Task[DocumentAccessibility] = {
    val listener = new SearchResponseListener(id)
    createSearchRequest(filter, document)
      .execute(listener)
    listener.result
  }

  override def verifyDocumentsAccessibilities(documents: NonEmptyList[Document],
                                              filter: Filter,
                                              id: RequestContext.Id): Task[DocumentsAccessibilities] = {
    val listener = new MultiSearchResponseListener(documents, id)
    createMultiSearchRequest(filter, documents)
      .execute(listener)
    listener.result
  }

  private def createMultiSearchRequest(definedFilter: Filter,
                                       documents: NonEmptyList[Document]) = {
    documents
      .map(createSearchRequest(definedFilter, _))
      .foldLeft(nodeClient.prepareMultiSearch())(_ add _)
  }

  private def createSearchRequest(filter: Filter,
                                  document: Document): SearchRequestBuilder = {
    val wrappedQueryFromFilter = QueryBuilders.wrapperQuery(filter.value.value)
    val composedQuery = QueryBuilders
      .boolQuery()
      .filter(QueryBuilders.constantScoreQuery(wrappedQueryFromFilter))
      .filter(QueryBuilders.idsQuery().addIds(document.documentId.value))

    nodeClient
      .prepareSearch(document.index.value.value)
      .setQuery(composedQuery)
  }
}

object EsServerBasedRorClusterService {

  private final class SearchResponseListener(id: RequestContext.Id) extends ActionListener[SearchResponse]
    with Logging {

    private val promise = Promise[DocumentAccessibility]

    def result: Task[DocumentAccessibility] = Task.fromFuture(promise.future)

    override def onResponse(response: SearchResponse): Unit = {
      val accessibility = extractAccessibilityFrom(response)
      promise.success(accessibility)
    }

    override def onFailure(exception: Exception): Unit = {
      logger.error(s"[${id.show}] Could not verify get request. Blocking document", exception)
      promise.success(Inaccessible)
    }

    private def extractAccessibilityFrom(searchResponse: SearchResponse) = {
      if (searchResponse.getHits.getTotalHits.value == 0L) Inaccessible
      else Accessible
    }
  }

  private final class MultiSearchResponseListener(documents: NonEmptyList[Document],
                                                  id: RequestContext.Id)
    extends ActionListener[MultiSearchResponse]
      with Logging {

    private val promise = Promise[DocumentsAccessibilities]

    def result: Task[DocumentsAccessibilities] = Task.fromFuture(promise.future)

    override def onResponse(response: MultiSearchResponse): Unit = {
      val results = extractResultsFromSearchResponse(response)
      promise.success(zip(results, documents))
    }

    override def onFailure(exception: Exception): Unit = {
      logger.error(s"[${id.show}] Could not verify documents returned by multi get response. Blocking all returned documents", exception)
      val results = blockAllDocsReturned(documents)
      promise.success(zip(results, documents))
    }

    private def blockAllDocsReturned(docsToVerify: NonEmptyList[Document]) = {
      List.fill(docsToVerify.size)(Inaccessible)
    }

    private def extractResultsFromSearchResponse(multiSearchResponse: MultiSearchResponse) = {
      multiSearchResponse
        .getResponses
        .map(resolveAccessibilityBasedOnSearchResult)
        .toList
    }

    private def resolveAccessibilityBasedOnSearchResult(mSearchItem: MultiSearchResponse.Item): DocumentAccessibility = {
      if (mSearchItem.isFailure) Inaccessible
      else if (mSearchItem.getResponse.getHits.getTotalHits.value == 0L) Inaccessible
      else Accessible
    }

    private def zip(results: List[DocumentAccessibility],
                    documents: NonEmptyList[Document]) = {
      documents.toList
        .zip(results)
        .toMap
    }
  }
}