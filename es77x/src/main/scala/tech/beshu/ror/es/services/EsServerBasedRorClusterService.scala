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
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.ActionListener
import org.elasticsearch.action.search.{SearchRequestBuilder, SearchResponse}
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.index.query.QueryBuilders
import tech.beshu.ror.accesscontrol.domain.DocumentAccessibility.{Accessible, Inaccessible}
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.RorClusterService._
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
                                           filter: Filter): Task[DocumentAccessibility] = {
    val listener = new SearchResponseListener
    createSearchRequest(filter, document)
      .execute(listener)
    listener.result
  }

  override def verifyDocumentsAccessibilities(documents: NonEmptyList[Document],
                                              filter: Filter): Task[DocumentsAccessibilities] = {
    Task.now(Map.empty)
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

  private final class SearchResponseListener extends ActionListener[SearchResponse] {

    private val promise = Promise[DocumentAccessibility]

    override def onResponse(response: SearchResponse): Unit = {
      val accessibility = extractAccessibilityFrom(response)
      promise.success(accessibility)
    }

    override def onFailure(exception: Exception): Unit = {
      logger.error(s"[id] Could not verify get request. Blocking document", exception)
      promise.success(Inaccessible)
    }

    private def extractAccessibilityFrom(searchResponse: SearchResponse) = {
      if (searchResponse.getHits.getTotalHits.value == 0L) Inaccessible
      else Accessible
    }

    def result: Task[DocumentAccessibility] = Task.fromFuture(promise.future)
  }
}