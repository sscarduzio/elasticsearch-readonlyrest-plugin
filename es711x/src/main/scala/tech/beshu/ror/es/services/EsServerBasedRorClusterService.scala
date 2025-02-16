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
import cats.implicits.*
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import monix.execution.CancelablePromise
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.ActionListener
import org.elasticsearch.action.admin.indices.resolve.ResolveIndexAction
import org.elasticsearch.action.admin.indices.resolve.ResolveIndexAction.ResolvedIndex
import org.elasticsearch.action.search.{MultiSearchResponse, SearchRequestBuilder, SearchResponse}
import org.elasticsearch.client.Client
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.cluster.metadata.{IndexMetadata, Metadata, RepositoriesMetadata}
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.index.query.QueryBuilders
import org.elasticsearch.repositories.{RepositoriesService, RepositoryData}
import org.elasticsearch.snapshots.SnapshotId
import org.elasticsearch.threadpool.ThreadPool
import org.elasticsearch.transport.RemoteClusterService
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote.ClusterName
import tech.beshu.ror.accesscontrol.domain.DataStreamName.{FullLocalDataStreamWithAliases, FullRemoteDataStreamWithAliases}
import tech.beshu.ror.accesscontrol.domain.DocumentAccessibility.{Accessible, Inaccessible}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.RorClusterService.*
import tech.beshu.ror.es.utils.ActionListenerToTaskAdapter
import tech.beshu.ror.es.utils.CallActionRequestAndHandleResponse.*
import tech.beshu.ror.es.utils.EsCollectionsScalaUtils.*
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import java.util.function.Supplier
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

class EsServerBasedRorClusterService(nodeName: String,
                                     clusterService: ClusterService,
                                     remoteClusterServiceSupplier: Supplier[Option[RemoteClusterService]],
                                     repositoriesServiceSupplier: Supplier[Option[RepositoriesService]],
                                     nodeClient: NodeClient,
                                     threadPool: ThreadPool)
  extends RorClusterService
    with Logging {

  import EsServerBasedRorClusterService.*

  override def indexOrAliasUuids(indexOrAlias: IndexOrAlias): Set[IndexUuid] = {
    val lookup = clusterService.state.metadata.getIndicesLookup
    lookup.get(indexOrAlias.stringify).getIndices.asScala.map(_.getIndexUUID).toCovariantSet
  }

  override def allIndicesAndAliases: Set[FullLocalIndexWithAliases] = {
    val metadata = clusterService.state.metadata
    extractIndicesAndAliasesFrom(metadata)
  }

  override def allRemoteIndicesAndAliases: Task[Set[FullRemoteIndexWithAliases]] = {
    remoteClusterServiceSupplier.get() match {
      case Some(remoteClusterService) =>
        provideAllRemoteIndices(remoteClusterService)
      case None =>
        Task.now(Set.empty)
    }
  }

  override def allDataStreamsAndAliases: Set[FullLocalDataStreamWithAliases] = {
    val metadata = clusterService.state.metadata
    extractDataStreamsAndAliases(metadata)
  }

  override def allRemoteDataStreamsAndAliases: Task[Set[FullRemoteDataStreamWithAliases]] =
    remoteClusterServiceSupplier.get() match {
      case Some(remoteClusterService) =>
        provideAllRemoteDataStreams(remoteClusterService)
      case None =>
        Task.now(Set.empty)
    }

  override def allTemplates: Set[Template] = {
    legacyTemplates() ++ indexTemplates() ++ componentTemplates()
  }

  override def allSnapshots: Map[RepositoryName.Full, Task[Set[SnapshotName.Full]]] = {
    determineAllSnapshots()
  }

  private def determineAllSnapshots(): Map[RepositoryName.Full, Task[Set[SnapshotName.Full]]] = {
    val repositoriesMetadata: RepositoriesMetadata = clusterService.state().metadata().custom(RepositoriesMetadata.TYPE)
    repositoriesMetadata
      .repositories().asSafeList
      .flatMap { repositoryMetadata =>
        RepositoryName
          .from(repositoryMetadata.name())
          .flatMap {
            case r: RepositoryName.Full => Some(r)
            case _ => None
          }
          .map { name =>
            (name, allSnapshotsFrom(name))
          }
      }
      .toMap
  }

  override def verifyDocumentAccessibility(document: Document,
                                           filter: Filter,
                                           id: RequestContext.Id): Task[DocumentAccessibility] = {
    createSearchRequest(filter, document)
      .call(extractAccessibilityFrom)
      .onErrorRecover {
        case ex =>
          logger.error(s"[${id.show}] Could not verify get request. Blocking document", ex)
          Inaccessible
      }
  }

  override def verifyDocumentsAccessibilities(documents: NonEmptyList[Document],
                                              filter: Filter,
                                              id: RequestContext.Id): Task[DocumentsAccessibilities] = {
    createMultiSearchRequest(filter, documents)
      .call(extractResultsFromSearchResponse)
      .onErrorRecover {
        case ex =>
          logger.error(s"[${id.show}] Could not verify documents returned by multi get response. Blocking all returned documents", ex)
          blockAllDocsReturned(documents)
      }
      .map(results => zip(results, documents))
  }

  private def extractIndicesAndAliasesFrom(metadata: Metadata) = {
    val indices = metadata.getIndices
    indices
      .keysIt().asScala
      .flatMap { index =>
        val indexMetaData = indices.get(index)
        IndexName.Full
          .fromString(indexMetaData.getIndex.getName)
          .map { indexName =>
            val aliases = indexMetaData.getAliases.asSafeKeys.flatMap(IndexName.Full.fromString)
            FullLocalIndexWithAliases(
              indexName,
              indexMetaData.getState match {
                case IndexMetadata.State.CLOSE => IndexAttribute.Closed
                case IndexMetadata.State.OPEN => IndexAttribute.Opened
              },
              aliases
            )
          }
      }
      .toCovariantSet
  }

  private def extractDataStreamsAndAliases(metadata: Metadata): Set[FullLocalDataStreamWithAliases] = {
    backingIndicesPerDataStreamFrom(metadata)
      .map { case (dataStreamName, backingIndices) =>
        FullLocalDataStreamWithAliases(
          dataStreamName = dataStreamName,
          aliasesNames = Set.empty, // aliases for data streams not supported
          backingIndices = backingIndices
        )
      }
      .toCovariantSet
  }

  private def backingIndicesPerDataStreamFrom(metadata: Metadata): Map[DataStreamName.Full, Set[IndexName.Full]] = {
    val dataStreams = metadata.dataStreams()
    dataStreams
      .keySet().asScala
      .flatMap { dataStreamName =>
        val dataStream = dataStreams.get(dataStreamName)
        val backingIndices =
          dataStream
            .getIndices.asScala
            .map(_.getName)
            .flatMap(
              IndexName.Full.fromString
            )
            .toCovariantSet

        DataStreamName.Full
          .fromString(dataStream.getName)
          .map(dataStreamName => (dataStreamName, backingIndices))
      }
      .toMap
  }

  private def provideAllRemoteDataStreams(remoteClusterService: RemoteClusterService) = {
    val remoteClusterFullNames =
      remoteClusterService
        .getRegisteredRemoteClusterNames.asSafeSet
        .flatMap(ClusterName.Full.fromString)

    Task
      .parSequenceUnordered(
        remoteClusterFullNames.map(resolveAllRemoteDataStreams(_, remoteClusterService))
      )
      .map(_.flatten.toCovariantSet)
  }

  private def resolveAllRemoteDataStreams(remoteClusterName: ClusterName.Full,
                                          remoteClusterService: RemoteClusterService): Task[List[FullRemoteDataStreamWithAliases]] = {
    Try(remoteClusterService.getRemoteClusterClient(threadPool, remoteClusterName.value.value)) match {
      case Failure(_) =>
        logger.error(s"Cannot get remote cluster client for remote cluster with name: ${remoteClusterName.show}")
        Task.now(List.empty)
      case Success(client) =>
        resolveRemoteIndicesUsing(client)
          .map { response =>
            remoteDataStreamsFrom(response, remoteClusterName)
          }
    }
  }

  private def remoteDataStreamsFrom(response: ResolveIndexAction.Response,
                                    remoteClusterName: ClusterName.Full): List[FullRemoteDataStreamWithAliases] = {
    response
      .getDataStreams.asSafeList
      .flatMap { resolvedDataStream =>
        IndexName.Full.fromString(resolvedDataStream.getName)
          .map { dataStreamName =>
            val backingIndices =
              resolvedDataStream
                .getBackingIndices.asSafeList
                .flatMap(IndexName.Full.fromString)
                .toCovariantSet

            FullRemoteDataStreamWithAliases(
              clusterName = remoteClusterName,
              dataStreamName = DataStreamName.Full.fromNes(dataStreamName.name),
              aliasesNames = Set.empty, // aliases for data streams not supported
              backingIndices = backingIndices
            )
          }
      }
  }

  private def provideAllRemoteIndices(remoteClusterService: RemoteClusterService) = {
    val remoteClusterFullNames =
      remoteClusterService
        .getRegisteredRemoteClusterNames.asSafeSet
        .flatMap(ClusterName.Full.fromString)

    Task
      .parSequenceUnordered(
        remoteClusterFullNames.map(resolveAllRemoteIndices(_, remoteClusterService))
      )
      .map(_.flatten.toCovariantSet)
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
              .getIndices.asSafeList
              .flatMap { resolvedIndex =>
                toFullRemoteIndexWithAliases(resolvedIndex, remoteClusterName)
              }
          }
    }
  }

  private def resolveRemoteIndicesUsing(client: Client) = {
    import tech.beshu.ror.es.utils.ThreadContextOps.*
    threadPool.getThreadContext.addXpackSecurityAuthenticationHeader(nodeName)
    val promise = CancelablePromise[ResolveIndexAction.Response]()
    client
      .admin()
      .indices()
      .resolveIndex(
        new ResolveIndexAction.Request(List("*").toArray),
        new ActionListener[ResolveIndexAction.Response] {
          override def onResponse(response: ResolveIndexAction.Response): Unit = promise.trySuccess(response)
          override def onFailure(e: Exception): Unit = promise.tryFailure(e)
        }
      )
    Task.fromCancelablePromise(promise)
  }

  private def toFullRemoteIndexWithAliases(resolvedIndex: ResolvedIndex,
                                           remoteClusterName: ClusterName.Full) = {
    IndexName.Full
      .fromString(resolvedIndex.getName)
      .map { index =>
        FullRemoteIndexWithAliases(remoteClusterName, index, indexAttributeFrom(resolvedIndex), aliasesFrom(resolvedIndex))
      }
  }

  private def aliasesFrom(resolvedIndex: ResolvedIndex) = {
    resolvedIndex
      .getAliases.asSafeList
      .flatMap(IndexName.Full.fromString)
      .toCovariantSet
  }

  private def indexAttributeFrom(resolvedIndex: ResolvedIndex): IndexAttribute = {
    resolvedIndex
      .getAttributes.toCovariantSet
      .find(_.toLowerCase == "CLOSED") match {
      case Some(_) => IndexAttribute.Closed
      case None => IndexAttribute.Opened
    }
  }

  private def allSnapshotsFrom(repository: RepositoryName.Full): Task[Set[SnapshotName.Full]] = {
    repositoriesServiceSupplier.get() match {
      case Some(repositoriesService) =>
        repositoriesService
          .getSnapshotIds(repository)
          .map { ids =>
            ids.flatMap { snapshotId =>
              snapshotFullNameFrom(snapshotId)
            }
          }
      case None =>
        logger.error("Cannot supply Snapshots Service. Please, report the issue!!!")
        Task.now(Set.empty)
    }
  }

  private def snapshotFullNameFrom(id: SnapshotId): Option[SnapshotName.Full] = {
    SnapshotName
      .from(id.getName)
      .flatMap {
        case SnapshotName.Wildcard => None
        case SnapshotName.All => None
        case SnapshotName.Pattern(_) => None
        case f: SnapshotName.Full => Some(f)
      }
  }

  private def legacyTemplates(): Set[Template.LegacyTemplate] = {
    val templates = clusterService.state.metadata().templates()
    templates
      .keysIt().asScala
      .flatMap { templateNameString =>
        val templateMetaData = templates.get(templateNameString)
        for {
          templateName <- NonEmptyString.unapply(templateNameString).map(TemplateName.apply)
          indexPatterns <- UniqueNonEmptyList.from(
            templateMetaData.patterns().asScala.flatMap(IndexPattern.fromString)
          )
          aliases = templateMetaData.aliases().asSafeValues.flatMap(a => ClusterIndexName.fromString(a.alias())).toCovariantSet
        } yield Template.LegacyTemplate(templateName, indexPatterns, aliases)
      }
      .toCovariantSet
  }

  private def indexTemplates(): Set[Template.IndexTemplate] = {
    val templates = clusterService.state.metadata().templatesV2()
    templates
      .keySet().asScala
      .flatMap { templateNameString =>
        val templateMetaData = templates.get(templateNameString)
        for {
          templateName <- NonEmptyString.unapply(templateNameString).map(TemplateName.apply)
          indexPatterns <- UniqueNonEmptyList.from(
            templateMetaData.indexPatterns().asScala.flatMap(IndexPattern.fromString)
          )
          aliases = Option(templateMetaData.template()).toCovariantSet
            .flatMap(_.aliases().asSafeMap.values.flatMap(a => ClusterIndexName.fromString(a.alias())).toCovariantSet)
        } yield Template.IndexTemplate(templateName, indexPatterns, aliases)
      }
      .toCovariantSet
  }

  private def componentTemplates(): Set[Template.ComponentTemplate] = {
    val templates = clusterService.state.metadata().componentTemplates()
    templates
      .keySet().asScala
      .flatMap { templateNameString =>
        val templateMetaData = templates.get(templateNameString)
        for {
          templateName <- NonEmptyString.unapply(templateNameString).map(TemplateName.apply)
          aliases = templateMetaData.template().aliases().asSafeMap.values.flatMap(a => ClusterIndexName.fromString(a.alias())).toCovariantSet
        } yield Template.ComponentTemplate(templateName, aliases)
      }
      .toCovariantSet
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
object EsServerBasedRorClusterService {

  private implicit class RepositoryServiceOps(val service: RepositoriesService) extends AnyVal {

    def getSnapshotIds(repository: RepositoryName.Full): Task[Set[SnapshotId]] = {
      val listener = new ActionListenerToTaskAdapter[RepositoryData]()
      service.getRepositoryData(RepositoryName.toString(repository), listener)
      listener.result.map(_.getSnapshotIds.asSafeSet)
    }
  }

}