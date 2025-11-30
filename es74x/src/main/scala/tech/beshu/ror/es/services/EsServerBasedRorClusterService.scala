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
import tech.beshu.ror.utils.RequestIdAwareLogging
import org.elasticsearch.action.ActionListener
import org.elasticsearch.action.admin.cluster.state.{ClusterStateRequest, ClusterStateResponse}
import org.elasticsearch.action.search.{MultiSearchResponse, SearchRequestBuilder, SearchResponse}
import org.elasticsearch.client.Client
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.cluster.metadata.{IndexMetaData, MetaData, RepositoriesMetaData}
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.index.query.QueryBuilders
import org.elasticsearch.repositories.{RepositoriesService, RepositoryData}
import org.elasticsearch.snapshots.{SnapshotId, SnapshotInfo}
import org.elasticsearch.snapshots.SnapshotsService
import org.elasticsearch.threadpool.ThreadPool
import org.elasticsearch.transport.RemoteClusterService
import org.joor.Reflect.on
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote.ClusterName
import tech.beshu.ror.accesscontrol.domain.DataStreamName.{FullLocalDataStreamWithAliases, FullRemoteDataStreamWithAliases}
import tech.beshu.ror.accesscontrol.domain.DocumentAccessibility.{Accessible, Inaccessible}
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.RorClusterService.*
import tech.beshu.ror.es.utils.ActionListenerToTaskAdapter
import tech.beshu.ror.es.utils.CallActionRequestAndHandleResponse.*
import tech.beshu.ror.es.utils.EsCollectionsScalaUtils.*
import tech.beshu.ror.es.utils.EsVersionAwareReflectionBasedSnapshotServiceAdapter
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*
import tech.beshu.ror.utils.set.CovariantSet
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import java.util.function.Supplier
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

class EsServerBasedRorClusterService(nodeName: String,
                                     clusterService: ClusterService,
                                     remoteClusterServiceSupplier: Supplier[Option[RemoteClusterService]],
                                     snapshotsServiceSupplier: Supplier[Option[SnapshotsService]],
                                     nodeClient: NodeClient,
                                     threadPool: ThreadPool)
  extends RorClusterService
    with RequestIdAwareLogging {

  import EsServerBasedRorClusterService.*

  override def indexOrAliasUuids(indexOrAlias: IndexOrAlias): Set[IndexUuid] = {
    val lookup = clusterService.state.metaData.getAliasAndIndexLookup
    lookup.get(indexOrAlias.stringify).getIndices.asScala.map(_.getIndexUUID).toCovariantSet
  }

  override def allIndicesAndAliases: Set[FullLocalIndexWithAliases] = {
    val metadata = clusterService.state.metaData()
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

  override def allDataStreamsAndAliases: Set[FullLocalDataStreamWithAliases] =
    Set.empty // data streams not supported

  override def allRemoteDataStreamsAndAliases: Task[Set[FullRemoteDataStreamWithAliases]] =
    Task.now(Set.empty) // data streams not supported

  override def allTemplates: Set[Template] = legacyTemplates()

  override def allSnapshots: Map[RepositoryName.Full, Task[Set[SnapshotName.Full]]] = {
    determineAllSnapshots().view.mapValues(_.map(_.map(_.name))).toMap
  }

  override def snapshotIndices(repositoryName: RepositoryName.Full,
                               snapshotName: SnapshotName.Full): Task[Set[ClusterIndexName]] = {
    determineAllSnapshots().get(repositoryName) match {
      case Some(getSnapshots) =>
        val snapshotNameMatcher = PatternsMatcher.create((snapshotName: SnapshotName) :: Nil)
        getSnapshots
          .flatMap { snapshots =>
            snapshots
              .map {
                case snapshot if snapshotNameMatcher.`match`(snapshot.name) =>
                  snapshot.fetchIndices
                case _ =>
                  Task.now(Set.empty)
              }
              .sequence
              .map(_.flatten)
          }
      case None =>
        Task.now(Set.empty)
    }
  }

  private def determineAllSnapshots(): Map[RepositoryName.Full, Task[Set[Snapshot]]] = {
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
          logger.error(s"Could not verify get request. Blocking document", ex)(id)
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
          logger.error(s"Could not verify documents returned by multi get response. Blocking all returned documents", ex)(id)
          blockAllDocsReturned(documents)
      }
      .map(results => zip(results, documents))
  }

  private def extractIndicesAndAliasesFrom(metadata: MetaData) = {
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
                case IndexMetaData.State.CLOSE => IndexAttribute.Closed
                case IndexMetaData.State.OPEN => IndexAttribute.Opened
              },
              aliases
            )
          }
      }
      .toCovariantSet
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
        noRequestIdLogger.error(s"Cannot get remote cluster client for remote cluster with name: ${remoteClusterName.show}")
        Task.now(List.empty)
      case Success(client) =>
        resolveRemoteIndicesUsing(client)
          .map { response =>
            response
              .getState
              .metaData()
              .indices().asSafeValues
              .flatMap { indexMetadata =>
                toFullRemoteIndexWithAliases(indexMetadata, remoteClusterName)
              }
          }
    }
  }

  private def resolveRemoteIndicesUsing(client: Client) = {
    import tech.beshu.ror.es.utils.ThreadContextOps.*
    threadPool.getThreadContext.addXpackSecurityAuthenticationHeader(nodeName)
    val promise = CancelablePromise[ClusterStateResponse]()
    client
      .admin()
      .cluster()
      .state(
        new ClusterStateRequest().metaData(true),
        new ActionListener[ClusterStateResponse] {
          override def onResponse(response: ClusterStateResponse): Unit = promise.trySuccess(response)
          override def onFailure(e: Exception): Unit = promise.tryFailure(e)
        }
      )
    Task.fromCancelablePromise(promise)
  }

  private def toFullRemoteIndexWithAliases(indexMetadata: IndexMetaData,
                                           remoteClusterName: ClusterName.Full) = {
    IndexName.Full
      .fromString(indexMetadata.getIndex.getName)
      .map { index =>
        FullRemoteIndexWithAliases(remoteClusterName, index, indexAttributeFrom(indexMetadata), aliasesFrom(indexMetadata))
      }
  }

  private def aliasesFrom(indexMetadata: IndexMetaData) = {
    indexMetadata
      .getAliases.asSafeValues
      .map(_.alias())
      .flatMap(IndexName.Full.fromString)
  }

  private def indexAttributeFrom(indexMetadata: IndexMetaData): IndexAttribute = {
    indexMetadata.getState.name().toUpperCase() match {
      case "CLOSED" => IndexAttribute.Closed
      case _ => IndexAttribute.Opened
    }
  }

  private def allSnapshotsFrom(repository: RepositoryName.Full): Task[Set[Snapshot]] = {
    snapshotsServiceSupplier.get() match {
      case Some(snapshotsService) =>
        snapshotsService
          .getSnapshotIds(repository)
          .map { ids =>
            val repositoriesService = snapshotsService.getRepositoriesService
            ids.flatMap { snapshotId =>
              snapshotFullNameFrom(snapshotId).map {
                name => Snapshot(name, repositoriesService.getSnapshotIndices(repository, snapshotId))
              }
            }
          }
      case None =>
        noRequestIdLogger.error("Cannot supply Snapshots Service. Please, report the issue!!!")
        Task.now(Set.empty[Snapshot])
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

  private final class Snapshot(val name: SnapshotName.Full,
                               val fetchIndices: Task[Set[ClusterIndexName]])

  private def legacyTemplates(): Set[Template.LegacyTemplate] = {
    val templates = clusterService.state.metaData().templates()
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

  private implicit class SnapshotsServiceOps(val service: SnapshotsService) extends AnyVal {

    def getSnapshotIds(repository: RepositoryName.Full): Task[Set[SnapshotId]] = {
      new EsVersionAwareReflectionBasedSnapshotServiceAdapter(service)
        .getRepositoryData(repository)
        .map(_.getSnapshotIds.asSafeSet)
    }

    def getRepositoriesService: RepositoriesService =
      on(service).get[RepositoriesService]("repositoriesService")
  }

  private implicit class RepositoriesServiceOps(val service: RepositoriesService) extends AnyVal {

    def getSnapshotIndices(repository: RepositoryName.Full, snapshotId: SnapshotId): Task[Set[ClusterIndexName]] = {
      Task.delay {
        indicesFrom {
          service
            .repository(repository.value.value)
            .getSnapshotInfo(snapshotId)
        }
      }
    }

    private def indicesFrom(snapshotInfo: SnapshotInfo) = {
      snapshotInfo
        .indices().asScala.toCovariantSet
        .flatMap(ClusterIndexName.fromString)
    }
  }

}