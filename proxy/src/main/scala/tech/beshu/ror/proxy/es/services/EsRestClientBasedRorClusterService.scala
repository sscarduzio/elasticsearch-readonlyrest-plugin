/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.services

import cats.data.NonEmptyList
import cats.implicits._
import monix.eval.Task
import monix.execution.Scheduler
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesRequest
import org.elasticsearch.action.admin.indices.template.get.GetIndexTemplatesRequest
import org.elasticsearch.action.search.{MultiSearchRequest, MultiSearchResponse, SearchResponse}
import org.elasticsearch.client.Requests
import org.elasticsearch.client.indices.GetIndexRequest
import org.elasticsearch.cluster.metadata.{AliasMetadata, IndexMetadata, IndexTemplateMetadata}
import org.elasticsearch.index.query.QueryBuilders
import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.DocumentAccessibility.{Accessible, Inaccessible}
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.RorClusterService._
import tech.beshu.ror.proxy.es.clients.RestHighLevelClientAdapter
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.collection.JavaConverters._

// todo: we need to refactor ROR to be able to use here async API
class EsRestClientBasedRorClusterService(client: RestHighLevelClientAdapter)
                                        (implicit scheduler: Scheduler)
  extends RorClusterService with Logging {

  override def indexOrAliasUuids(indexOrAlias: IndexOrAlias): Set[IndexUuid] = {
    client
      .getIndex(new GetIndexRequest(indexOrAlias.value.value))
      .map { response =>
        Option(response.getSetting(indexOrAlias.value.value, IndexMetadata.INDEX_UUID_NA_VALUE)).toSet
      }
      .runSyncUnsafe()
  }

  override def allIndicesAndAliases: Map[IndexName, Set[AliasName]] = {
    client
      .getAlias(new GetAliasesRequest())
      .map { response =>
        response
          .getAliases.asScala
          .flatMap { case (indexNameString, aliases) =>
            indexWithAliasesFrom(indexNameString, aliases.asScala.toSet)
          }
          .toMap
      }
      .runSyncUnsafe()
  }

  override def allTemplates: Set[TemplateLike] = {
    client
      .getTemplate(new GetIndexTemplatesRequest())
      .map { response =>
        response
          .getIndexTemplates.asScala
          .flatMap(templateFrom)
          .toSet
      }
      .runSyncUnsafe()
  }

  override def getTemplate(name: TemplateName): Option[TemplateLike] = {
    allTemplates.find(_.name === name)
  }

  override def verifyDocumentAccessibility(document: Document,
                                           filter: domain.Filter,
                                           id: RequestContext.Id): Task[domain.DocumentAccessibility] = {
    client
      .search(createSearchRequest(filter, document))
      .map(extractAccessibilityFrom)
      .onErrorRecover {
        case ex =>
          logger.error(s"[${id.show}] Could not verify get request. Blocking document", ex)
          Inaccessible
      }
  }

  override def verifyDocumentsAccessibilities(documents: NonEmptyList[Document],
                                              filter: domain.Filter,
                                              id: RequestContext.Id): Task[DocumentsAccessibilities] = {
    client
      .mSearch(createMultiSearchRequest(filter, documents))
      .map(extractResultsFromSearchResponse)
      .onErrorRecover {
        case ex =>
          logger.error(s"[${id.show}] Could not verify documents returned by multi get response. Blocking all returned documents", ex)
          blockAllDocsReturned(documents)
      }
      .map(results => zip(results, documents))
  }

  private def indexWithAliasesFrom(indexNameString: String, aliasMetadata: Set[AliasMetadata]) = {
    IndexName
      .fromString(indexNameString)
      .map { index =>
        (index, aliasMetadata.flatMap(am => IndexName.fromString(am.alias())))
      }
  }

  // todo:
  private def templateFrom(metaData: IndexTemplateMetadata): Option[TemplateLike] = {
    TemplateName
      .fromString(metaData.name())
      .flatMap { templateName =>
        UniqueNonEmptyList
          .fromList {
            metaData
              .patterns().asScala.toList
              .flatMap(IndexName.fromString)
          }
          .map { patterns =>
            val aliases = metaData.aliases().valuesIt().asScala.flatMap(a => IndexName.fromString(a.alias())).toSet
            TemplateLike.IndexTemplate(templateName, patterns, aliases)
          }
      }
  }

  private def extractAccessibilityFrom(searchResponse: SearchResponse) = {
    if (searchResponse.getHits.getTotalHits.value == 0L) Inaccessible
    else Accessible
  }

  private def createSearchRequest(filter: Filter,
                                  document: Document) = {
    val wrappedQueryFromFilter = QueryBuilders.wrapperQuery(filter.value.value)
    val composedQuery = QueryBuilders
      .boolQuery()
      .filter(QueryBuilders.constantScoreQuery(wrappedQueryFromFilter))
      .filter(QueryBuilders.idsQuery().addIds(document.documentId.value))

    val searchRequest = Requests.searchRequest(document.index.value.value)
    searchRequest.source().query(composedQuery)
    searchRequest
  }

  private def createMultiSearchRequest(definedFilter: Filter,
                                       documents: NonEmptyList[Document]) = {
    documents
      .map(createSearchRequest(definedFilter, _))
      .foldLeft(new MultiSearchRequest)(_ add _)
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