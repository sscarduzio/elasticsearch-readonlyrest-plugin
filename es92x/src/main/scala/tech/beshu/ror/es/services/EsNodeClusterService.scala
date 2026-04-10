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
import monix.execution.atomic.Atomic
import monix.execution.{CancelablePromise, Scheduler}
import org.elasticsearch.action.ActionListener
import org.elasticsearch.action.admin.indices.resolve.ResolveIndexAction
import org.elasticsearch.action.admin.indices.resolve.ResolveIndexAction.{ResolvedAlias, ResolvedIndex}
import org.elasticsearch.action.search.{MultiSearchResponse, SearchRequestBuilder, SearchResponse}
import org.elasticsearch.client.internal.RemoteClusterClient
import org.elasticsearch.client.internal.node.NodeClient
import org.elasticsearch.cluster.ClusterChangedEvent
import org.elasticsearch.cluster.metadata.{IndexMetadata, Metadata, ProjectMetadata, RepositoriesMetadata}
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.index.query.QueryBuilders
import org.elasticsearch.repositories.{RepositoriesService, RepositoryData}
import org.elasticsearch.snapshots.{SnapshotId, SnapshotInfo}
import org.elasticsearch.threadpool.ThreadPool
import org.elasticsearch.transport.RemoteClusterService
import org.elasticsearch.transport.RemoteClusterService.DisconnectedStrategy
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote.ClusterName
import tech.beshu.ror.accesscontrol.domain.DataStreamName.{FullLocalDataStreamWithAliases, FullRemoteDataStreamWithAliases}
import tech.beshu.ror.accesscontrol.domain.DocumentAccessibility.{Accessible, Inaccessible}
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.es.services.EsClusterService.*
import tech.beshu.ror.es.utils.ActionListenerToTaskAdapter
import tech.beshu.ror.es.utils.CallActionRequestAndHandleResponse.*
import tech.beshu.ror.es.utils.ClusterStateMetadataOps.toOps
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.set.CovariantSet
import tech.beshu.ror.utils.RequestIdAwareLogging
import tech.beshu.ror.utils.ScalaOps.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import java.util.function.Supplier
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success, Try}

class EsNodeClusterService(nodeName: String,
                           clusterService: ClusterService,
                           remoteClusterServiceSupplier: Supplier[Option[RemoteClusterService]],
                           repositoriesServiceSupplier: Supplier[Option[RepositoriesService]],
                           nodeClient: NodeClient,
                           threadPool: ThreadPool)
                          (implicit val scheduler: Scheduler)
  extends EsClusterService
    with RequestIdAwareLogging {

  import EsNodeClusterService.*

  private val localClusterSnapshotAtomic: Atomic[LocalClusterSnapshot] = Atomic {
    Option(clusterService.state) match {
      case Some(state) => LocalClusterSnapshot.from(state.metadata())
      case None => LocalClusterSnapshot.empty
    }
  }

  clusterService.addListener((event: ClusterChangedEvent) => {
    if (event.metadataChanged()) {
      val metadata = event.state().metadata()
      localClusterSnapshotAtomic.transform { current =>
        if (metadata.version() > current.version) LocalClusterSnapshot.from(metadata)
        else current
      }
    }
  })

  override def remoteClustersConfigured(implicit id: RequestId): Boolean = {
    remoteClusterServiceSupplier.get() match {
      case Some(remoteClusterService) => !remoteClusterService.getRegisteredRemoteClusterNames.isEmpty
      case None => false
    }
  }

  override def allRemoteClusterNames(implicit id: RequestId): Set[ClusterName.Full] = {
    remoteClusterServiceSupplier.get() match {
      case Some(remoteClusterService) =>
        remoteClusterService
          .getRemoteConnectionInfos.toList.asScala.toCovariantSet
          .map(_.getClusterAlias)
          .flatMap(ClusterName.Full.fromString)
      case None =>
        Set.empty
    }
  }

  override def indexOrAliasUuids(indexOrAlias: IndexOrAlias)
                                (implicit id: RequestId): Set[IndexUuid] = {
    val lookups = clusterService.state.metadata.projects().values().asScala.map(_.getIndicesLookup)
    lookups.flatMap(_.get(indexOrAlias.stringify).getIndices.asScala.map(_.getUUID)).toCovariantSet
  }

  override def allIndicesAndAliases(implicit id: RequestId): Set[FullLocalIndexWithAliases] =
    localClusterSnapshotAtomic.get().indices.raw

  override def localIndicesSnapshot(implicit id: RequestId): LocalIndicesSnapshot =
    localClusterSnapshotAtomic.get().indices

  override def allRemoteIndicesAndAliases(implicit id: RequestId): Task[Set[FullRemoteIndexWithAliases]] = {
    remoteClusterServiceSupplier.get() match {
      case Some(remoteClusterService) =>
        provideAllRemoteIndices(remoteClusterService)
      case None =>
        Task.now(Set.empty)
    }
  }

  override def allDataStreamsAndAliases(implicit id: RequestId): Set[FullLocalDataStreamWithAliases] =
    localClusterSnapshotAtomic.get().dataStreams.raw

  override def localDataStreamsSnapshot(implicit id: RequestId): LocalDataStreamsSnapshot =
    localClusterSnapshotAtomic.get().dataStreams

  override def allRemoteDataStreamsAndAliases(implicit id: RequestId): Task[Set[FullRemoteDataStreamWithAliases]] =
    remoteClusterServiceSupplier.get() match {
      case Some(remoteClusterService) =>
        provideAllRemoteDataStreams(remoteClusterService)
      case None =>
        Task.now(Set.empty)
    }

  override def legacyTemplates(implicit id: RequestId): Set[Template.LegacyTemplate] = {
    clusterService
      .state.metadata()
      .allTemplatesMetadata
      .flatMap { (name, metadata) =>
        for {
          templateName <- NonEmptyString.unapply(name).map(TemplateName.apply)
          indexPatterns <- UniqueNonEmptyList.from(metadata.patterns().asScala.flatMap(IndexPattern.fromString))
          aliases = metadata.aliases().asSafeValues.flatMap(a => ClusterIndexName.fromString(a.alias()))
        } yield Template.LegacyTemplate(templateName, indexPatterns, aliases)
      }
      .toCovariantSet
  }

  override def indexTemplates(implicit id: RequestId): Set[Template.IndexTemplate] = {
    clusterService
      .state.metadata()
      .allTemplatesV2Metadata
      .flatMap { (name, metadata) =>
        for {
          templateName <- NonEmptyString.unapply(name).map(TemplateName.apply)
          indexPatterns <- UniqueNonEmptyList.from(metadata.indexPatterns().asScala.flatMap(IndexPattern.fromString))
          aliases = Option(metadata.template()).toCovariantSet
            .flatMap(_.aliases().asSafeMap.values.flatMap(a => ClusterIndexName.fromString(a.alias())).toCovariantSet)
        } yield Template.IndexTemplate(templateName, indexPatterns, aliases)
      }
      .toCovariantSet
  }

  override def componentTemplates(implicit id: RequestId): Set[Template.ComponentTemplate] = {
    clusterService
      .state.metadata()
      .allComponentTemplatesMetadata
      .flatMap { (name, metadata) =>
        for {
          templateName <- NonEmptyString.unapply(name).map(TemplateName.apply)
          aliases = metadata.template().aliases().asSafeMap.values.flatMap(a => ClusterIndexName.fromString(a.alias())).toCovariantSet
        } yield Template.ComponentTemplate(templateName, aliases)
      }
      .toCovariantSet
  }

  override def allSnapshots(implicit id: RequestId): Map[RepositoryName.Full, Task[Set[SnapshotName.Full]]] = {
    determineAllSnapshots().view.mapValues(_.map(_.map(_.name))).toMap
  }

  override def snapshotIndices(repositoryName: RepositoryName.Full,
                               snapshotName: SnapshotName.Full)
                              (implicit id: RequestId): Task[Set[ClusterIndexName]] = {
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

  private def determineAllSnapshots()(implicit requestId: RequestId): Map[RepositoryName.Full, Task[Set[Snapshot]]] = {
    val projectsMetadata = clusterService.state.metadata.projects().values().asScala
    projectsMetadata
      .flatMap(pm => Option(RepositoriesMetadata.get(pm)))
      .flatMap {
        _.repositories().asSafeList
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
      }
      .toMap
  }

  override def verifyDocumentAccessibility(document: Document,
                                           filter: Filter)
                                          (implicit id: RequestId): Task[DocumentAccessibility] = {
    createSearchRequest(filter, document)
      .call(extractAccessibilityFrom)
      .onErrorRecover {
        case ex =>
          logger.error(s"Could not verify get request. Blocking document", ex)
          Inaccessible
      }
  }

  override def verifyDocumentsAccessibility(documents: NonEmptyList[Document],
                                            filter: Filter)
                                           (implicit id: RequestId): Task[DocumentsAccessibility] = {
    createMultiSearchRequest(filter, documents)
      .call(extractResultsFromSearchResponse)
      .onErrorRecover {
        case ex =>
          logger.error(s"Could not verify documents returned by multi get response. Blocking all returned documents", ex)
          blockAllDocsReturned(documents)
      }
      .map(results => zip(results, documents))
  }

  private def provideAllRemoteDataStreams(remoteClusterService: RemoteClusterService)
                                         (implicit requestId: RequestId) = {
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
                                          remoteClusterService: RemoteClusterService)
                                         (implicit requestId: RequestId): Task[List[FullRemoteDataStreamWithAliases]] = {
    getRemoteClusterClient(remoteClusterService, remoteClusterName) match {
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
    val aliasesPerIndex: Map[IndexName.Full, Set[IndexName.Full]] = aliasesPerIndexFrom(response.getAliases.asSafeList)
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

            val dataStreamAliases =
              backingIndices
                .flatMap(backingIndex => aliasesPerIndex.getOrElse(backingIndex, Set.empty))
                .map(index => DataStreamName.Full.fromNes(index.name))

            FullRemoteDataStreamWithAliases(
              clusterName = remoteClusterName,
              dataStreamName = DataStreamName.Full.fromNes(dataStreamName.name),
              aliasesNames = dataStreamAliases,
              backingIndices = backingIndices
            )
          }
      }
  }

  private def provideAllRemoteIndices(remoteClusterService: RemoteClusterService)
                                     (implicit requestId: RequestId) = {
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
                                      remoteClusterService: RemoteClusterService)
                                     (implicit requestId: RequestId) = {
    getRemoteClusterClient(remoteClusterService, remoteClusterName) match {
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

  private def getRemoteClusterClient(remoteClusterService: RemoteClusterService,
                                     remoteClusterName: ClusterName.Full) = {
    Try(remoteClusterService.getRemoteClusterClient(remoteClusterName.value.value, scheduler, DisconnectedStrategy.RECONNECT_IF_DISCONNECTED))
  }

  private def resolveRemoteIndicesUsing(client: RemoteClusterClient) = {
    import tech.beshu.ror.es.utils.ThreadContextOps.*
    threadPool.getThreadContext.addXpackUserAuthenticationHeader(nodeName)
    val promise = CancelablePromise[ResolveIndexAction.Response]()
    client
      .execute(
        ResolveIndexAction.REMOTE_TYPE,
        new ResolveIndexAction.Request(Array("*")),
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
        new FullRemoteIndexWithAliases(remoteClusterName, index, indexAttributeFrom(resolvedIndex), aliasesFrom(resolvedIndex))
      }
  }

  private def aliasesFrom(resolvedIndex: ResolvedIndex) = {
    resolvedIndex
      .getAliases.asSafeList
      .flatMap(IndexName.Full.fromString)
      .toCovariantSet
  }

  private def aliasesPerIndexFrom(resolvedAliases: List[ResolvedAlias]): Map[IndexName.Full, Set[IndexName.Full]] = {
    val result = mutable.HashMap.empty[IndexName.Full, mutable.Builder[IndexName.Full, Set[IndexName.Full]]]
    resolvedAliases.foreach { resolvedAlias =>
      IndexName.Full.fromString(resolvedAlias.getName).foreach { aliasName =>
        resolvedAlias
          .getIndices.asSafeList
          .flatMap(IndexName.Full.fromString)
          .foreach { index =>
            result.getOrElseUpdate(index, CovariantSet.newBuilder) += aliasName
          }
      }
    }
    result.view.mapValues(_.result()).toMap
  }

  private def indexAttributeFrom(resolvedIndex: ResolvedIndex): IndexAttribute = {
    resolvedIndex
      .getAttributes.toCovariantSet
      .find(_.toLowerCase == "CLOSED") match {
      case Some(_) => IndexAttribute.Closed
      case None => IndexAttribute.Opened
    }
  }

  private def allSnapshotsFrom(repository: RepositoryName.Full)
                              (implicit requestId: RequestId): Task[Set[Snapshot]] = {
    repositoriesServiceSupplier.get() match {
      case Some(repositoriesService) =>
        repositoriesService
          .getSnapshotIds(repository, clusterService.state.metadata)
          .map { ids =>
            ids.flatMap { snapshotId =>
              snapshotFullNameFrom(snapshotId).map { name =>
                Snapshot(name, repositoriesService.getSnapshotIndices(repository, snapshotId, clusterService.state.metadata))
              }
            }
          }
      case None =>
        logger.error("Cannot supply Snapshots Service. Please, report the issue!!!")
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

object EsNodeClusterService {

  private final class LocalClusterSnapshot private(val version: Long,
                                                   val indices: LocalIndicesSnapshot,
                                                   val dataStreams: LocalDataStreamsSnapshot)

  private object LocalClusterSnapshot {

    def from(metadata: Metadata): LocalClusterSnapshot = {
      new LocalClusterSnapshot(
        version = metadata.version(),
        indices = new LocalIndicesSnapshot(extractIndicesAndAliasesFrom(metadata)),
        dataStreams = new LocalDataStreamsSnapshot(extractDataStreamsAndAliases(metadata))
      )
    }

    val empty: LocalClusterSnapshot = new LocalClusterSnapshot(
      version = -1L,
      indices = new LocalIndicesSnapshot(Set.empty),
      dataStreams = new LocalDataStreamsSnapshot(Set.empty)
    )

    private def extractIndicesAndAliasesFrom(metadata: Metadata): Set[FullLocalIndexWithAliases] = {
      metadata
        .projects().values().asScala
        .flatMap(_.indices.values().asScala)
        .flatMap { indexMetaData =>
          IndexName.Full
            .fromString(indexMetaData.getIndex.getName)
            .map { indexName =>
              val aliases = indexMetaData.getAliases.asSafeMap.keys.flatMap(IndexName.Full.fromString).toCovariantSet
              new FullLocalIndexWithAliases(
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
      metadata
        .projects().values().asScala
        .flatMap { projectMetadata =>
          val aliasesPerDataStream = aliasesPerDataStreamFrom(projectMetadata)
          backingIndicesPerDataStreamFrom(projectMetadata)
            .map { case (dataStreamName, backingIndices) =>
              FullLocalDataStreamWithAliases(
                dataStreamName = dataStreamName,
                aliasesNames = aliasesPerDataStream.getOrElse(dataStreamName, Set.empty),
                backingIndices = backingIndices
              )
            }
        }
        .toCovariantSet
    }

    private def aliasesPerDataStreamFrom(metadata: ProjectMetadata): Map[DataStreamName.Full, Set[DataStreamName.Full]] = {
      val result = mutable.HashMap.empty[DataStreamName.Full, mutable.Builder[DataStreamName.Full, Set[DataStreamName.Full]]]
      val dataStreamAliases = metadata.dataStreamAliases()
      dataStreamAliases.keySet().asScala.foreach { aliasName =>
        val dataStreamAlias = dataStreamAliases.get(aliasName)
        DataStreamName.Full.fromString(dataStreamAlias.getName).foreach { alias =>
          dataStreamAlias
            .getDataStreams.asScala
            .flatMap(DataStreamName.Full.fromString)
            .foreach { ds =>
              result.getOrElseUpdate(ds, CovariantSet.newBuilder) += alias
            }
        }
      }
      result.view.mapValues(_.result()).toMap
    }

    private def backingIndicesPerDataStreamFrom(metadata: ProjectMetadata): Map[DataStreamName.Full, Set[IndexName.Full]] = {
      val dataStreams = metadata.dataStreams()
      dataStreams
        .keySet().asScala
        .flatMap { dataStreamName =>
          val dataStream = dataStreams.get(dataStreamName)
          val backingIndices =
            dataStream
              .getIndices.asScala
              .map(_.getName)
              .flatMap(IndexName.Full.fromString)
              .toCovariantSet

          DataStreamName.Full
            .fromString(dataStream.getName)
            .map(dataStreamName => (dataStreamName, backingIndices))
        }
        .toMap
    }

  }

  private implicit class RepositoryServiceOps(val service: RepositoriesService) extends AnyVal {

    def getSnapshotIds(repository: RepositoryName.Full, clusterMetadata: Metadata): Task[Set[SnapshotId]] = {
      Task
        .parSequence {
          clusterMetadata.projects().asScala.keys
            .map { projectId =>
              val listener = new ActionListenerToTaskAdapter[RepositoryData]()
              service.getRepositoryData(projectId, RepositoryName.toString(repository), listener)
              listener.result.map(_.getSnapshotIds.asScala)
            }
        }
        .map(_.flatten.toCovariantSet)
    }

    def getSnapshotIndices(repository: RepositoryName.Full,
                           snapshotId: SnapshotId,
                           clusterMetadata: Metadata): Task[Set[ClusterIndexName]] = {
      Task
        .parSequence {
          clusterMetadata.projects().asScala.keys
            .map { projectId =>
              val listener = new ActionListenerToTaskAdapter[SnapshotInfo]()
              service.repository(projectId, repository.value.value).getSnapshotInfo(snapshotId, listener)
              listener.result.map(indicesFrom)
            }
        }
        .map(_.flatten.toCovariantSet)
    }

    private def indicesFrom(snapshotInfo: SnapshotInfo) = {
      val allIndices = snapshotInfo
        .indices().asScala.toCovariantSet
        .flatMap(ClusterIndexName.fromString)
      val featureStateIndices = snapshotInfo
        .featureStates().asScala.toCovariantSet
        .flatMap(_.getIndices.asScala.toCovariantSet)
        .flatMap(ClusterIndexName.fromString)
      allIndices.diff(featureStateIndices)
    }
  }

}