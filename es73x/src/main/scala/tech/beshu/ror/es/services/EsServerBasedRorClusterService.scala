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

import java.util.function.Supplier
import cats.data.NonEmptyList
import cats.implicits._
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import monix.execution.CancelablePromise
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.ActionListener
import org.elasticsearch.action.admin.indices.get.GetIndexRequest.Feature
import org.elasticsearch.action.admin.indices.get.{GetIndexRequest, GetIndexResponse}
import org.elasticsearch.action.search.{MultiSearchResponse, SearchRequestBuilder, SearchResponse}
import org.elasticsearch.client.Client
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.cluster.metadata.RepositoriesMetaData
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.index.query.QueryBuilders
import tech.beshu.ror.accesscontrol.show.logs._
import org.elasticsearch.snapshots.SnapshotsService
import org.elasticsearch.threadpool.ThreadPool
import org.elasticsearch.transport.RemoteClusterService
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote.ClusterName
import tech.beshu.ror.accesscontrol.domain.DocumentAccessibility.{Accessible, Inaccessible}
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.RorClusterService._
import tech.beshu.ror.es.utils.EsCollectionsScalaUtils._
import tech.beshu.ror.es.utils.GenericResponseListener
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

class EsServerBasedRorClusterService(nodeName: String,
                                     clusterService: ClusterService,
                                     remoteClusterServiceSupplier: Supplier[Option[RemoteClusterService]],
                                     snapshotsServiceSupplier: Supplier[Option[SnapshotsService]],
                                     nodeClient: NodeClient,
                                     threadPool: ThreadPool)
  extends RorClusterService
    with Logging {

  override def indexOrAliasUuids(indexOrAlias: IndexOrAlias): Set[IndexUuid] = {
    val lookup = clusterService.state.metaData.getAliasAndIndexLookup
    lookup.get(indexOrAlias.stringify).getIndices.asScala.map(_.getIndexUUID).toSet
  }

  override def allIndicesAndAliases: Set[FullLocalIndexWithAliases] = {
    val indices = clusterService.state.metaData().getIndices
    indices
      .keysIt().asScala
      .flatMap { index =>
        val indexMetaData = indices.get(index)
        IndexName.Full
          .fromString(indexMetaData.getIndex.getName)
          .map { indexName =>
            val aliases = indexMetaData.getAliases.asSafeKeys.flatMap(IndexName.Full.fromString)
            FullLocalIndexWithAliases(indexName, aliases)
          }
      }
      .toSet
  }

  override def allRemoteIndicesAndAliases: Task[Set[FullRemoteIndexWithAliases]] = {
    remoteClusterServiceSupplier.get() match {
      case Some(remoteClusterService) =>
        provideAllRemoteIndices(remoteClusterService)
      case None =>
        Task.now(Set.empty)
    }
  }

  override def allTemplates: Set[Template] = legacyTemplates()

  override def allSnapshots: Map[RepositoryName.Full, Set[SnapshotName.Full]] = {
    val repositoriesMetadata: RepositoriesMetaData = clusterService.state().metaData().custom(RepositoriesMetaData.TYPE)
    repositoriesMetadata
      .repositories().asSafeList
      .flatMap { repositoryMetadata =>
        RepositoryName
          .from(repositoryMetadata.name())
          .flatMap {
            case r: RepositoryName.Full => Some(r)
            case _ => None
          }
          .map { name => (name, snapshotsBy(name)) }
      }
      .toMap
  }

  override def verifyDocumentAccessibility(document: Document,
                                           filter: Filter,
                                           id: RequestContext.Id): Task[DocumentAccessibility] = {
    val listener = new GenericResponseListener[SearchResponse]
    createSearchRequest(filter, document).execute(listener)

    listener.result
      .map(extractAccessibilityFrom)
      .onErrorRecover {
        case ex =>
          logger.error(s"[${id.show}] Could not verify get request. Blocking document", ex)
          Inaccessible
      }
  }

  override def verifyDocumentsAccessibilities(documents: NonEmptyList[Document],
                                              filter: Filter,
                                              id: RequestContext.Id): Task[DocumentsAccessibilities] = {
    val listener = new GenericResponseListener[MultiSearchResponse]
    createMultiSearchRequest(filter, documents).execute(listener)

    listener.result
      .map(extractResultsFromSearchResponse)
      .onErrorRecover {
        case ex =>
          logger.error(s"[${id.show}] Could not verify documents returned by multi get response. Blocking all returned documents", ex)
          blockAllDocsReturned(documents)
      }
      .map(results => zip(results, documents))
  }

  private def provideAllRemoteIndices(remoteClusterService: RemoteClusterService) = {
    val remoteClusterFullNames =
      remoteClusterService
        .getRegisteredRemoteClusterNames.asSafeSet
        .flatMap(ClusterName.Full.fromString)

    Task
      .gatherUnordered(
        remoteClusterFullNames.map(resolveAllRemoteIndices(_, remoteClusterService))
      )
      .map(_.flatten.toSet)
  }

  private def resolveAllRemoteIndices(remoteClusterName: ClusterName.Full,
                                      remoteClusterService: RemoteClusterService) = {
    Try(remoteClusterService.getRemoteClusterClient(threadPool, remoteClusterName.value.value)) match {
      case Failure(_) =>
        logger.error(s"Cannot get remote cluster client for remote cluster with name: ${remoteClusterName.show}")
        Task.now(List.empty)
      case Success(client) =>
        resolveRemoteIndicesUsing(client)
          .map { response =>
            response
              .indices().asSafeList
              .flatMap { index =>
                val aliases = response.aliases().get(index).asSafeList.map(_.alias())
                toFullRemoteIndexWithAliases(index, aliases, remoteClusterName)
              }
          }
    }
  }

  private def resolveRemoteIndicesUsing(client: Client) = {
    import tech.beshu.ror.es.utils.ThreadContextOps._
    threadPool.getThreadContext.addXpackSecurityAuthenticationHeader(nodeName)
    val promise = CancelablePromise[GetIndexResponse]()
    client
      .admin()
      .indices()
      .getIndex(
        new GetIndexRequest().features(Feature.ALIASES),
        new ActionListener[GetIndexResponse] {
          override def onResponse(response: GetIndexResponse): Unit = promise.trySuccess(response)
          override def onFailure(e: Exception): Unit = promise.tryFailure(e)
        })
    Task.fromCancelablePromise(promise)
  }

  private def toFullRemoteIndexWithAliases(indexName: String,
                                           aliasesNames: List[String],
                                           remoteClusterName: ClusterName.Full) = {
    IndexName.Full
      .fromString(indexName)
      .map { index =>
        val aliases = aliasesNames
          .flatMap(IndexName.Full.fromString)
          .toSet
        FullRemoteIndexWithAliases(remoteClusterName, index, aliases)
      }
  }

  private def snapshotsBy(repositoryName: RepositoryName) = {
    snapshotsServiceSupplier.get() match {
      case Some(snapshotsService) =>
        snapshotsService
          .getRepositoryData(RepositoryName.toString(repositoryName))
          .getAllSnapshotIds.asSafeIterable
          .flatMap { sId =>
            SnapshotName
              .from(sId.getName)
              .flatMap {
                case SnapshotName.Wildcard => None
                case SnapshotName.All => None
                case SnapshotName.Pattern(_) => None
                case f: SnapshotName.Full => Some(f)
              }
          }
          .toSet
      case None =>
        logger.error("Cannot supply Snapshots Service. Please, report the issue!!!")
        Set.empty[SnapshotName.Full]
    }
  }

  private def legacyTemplates(): Set[Template] = {
    val templates = clusterService.state.metaData().templates()
    templates
      .keysIt().asScala
      .flatMap { templateNameString =>
        val templateMetaData = templates.get(templateNameString)
        for {
          templateName <- NonEmptyString.unapply(templateNameString).map(TemplateName.apply)
          indexPatterns <- UniqueNonEmptyList.fromList(
            templateMetaData.patterns().asScala.flatMap(IndexPattern.fromString).toList
          )
          aliases = templateMetaData.aliases().asSafeValues.flatMap(a => ClusterIndexName.fromString(a.alias()))
        } yield Template.LegacyTemplate(templateName, indexPatterns, aliases)
      }
      .toSet
  }

  private def createSearchRequest(filter: Filter,
                                  document: Document): SearchRequestBuilder = {
    val wrappedQueryFromFilter = QueryBuilders.wrapperQuery(filter.value.value)
    val composedQuery = QueryBuilders
      .boolQuery()
      .filter(QueryBuilders.constantScoreQuery(wrappedQueryFromFilter))
      .filter(QueryBuilders.idsQuery().addIds(document.documentId.value))

    nodeClient
      .prepareSearch(document.index.stringify)
      .setQuery(composedQuery)
  }

  private def extractAccessibilityFrom(searchResponse: SearchResponse) = {
    if (searchResponse.getHits.getTotalHits.value == 0L) Inaccessible
    else Accessible
  }

  private def createMultiSearchRequest(definedFilter: Filter,
                                       documents: NonEmptyList[Document]) = {
    documents
      .map(createSearchRequest(definedFilter, _))
      .foldLeft(nodeClient.prepareMultiSearch())(_ add _)
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