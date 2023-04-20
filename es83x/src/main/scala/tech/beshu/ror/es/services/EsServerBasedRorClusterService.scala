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
import cats.kernel.Monoid
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import monix.execution.CancelablePromise
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.ActionListener
import org.elasticsearch.action.admin.indices.resolve.ResolveIndexAction
import org.elasticsearch.action.admin.indices.resolve.ResolveIndexAction.{ResolvedAlias, ResolvedIndex}
import org.elasticsearch.action.search.{MultiSearchResponse, SearchRequestBuilder, SearchResponse}
import org.elasticsearch.action.support.PlainActionFuture
import org.elasticsearch.client.internal.Client
import org.elasticsearch.client.internal.node.NodeClient
import org.elasticsearch.cluster.metadata.{IndexMetadata, Metadata, RepositoriesMetadata}
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.index.query.QueryBuilders
import org.elasticsearch.repositories.{RepositoriesService, RepositoryData}
import org.elasticsearch.threadpool.ThreadPool
import org.elasticsearch.transport.RemoteClusterService
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote.ClusterName
import tech.beshu.ror.accesscontrol.domain.DataStreamName.{FullLocalDataStreamWithAliases, FullRemoteDataStreamWithAliases}
import tech.beshu.ror.accesscontrol.domain.DocumentAccessibility.{Accessible, Inaccessible}
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.RorClusterService._
import tech.beshu.ror.es.utils.GenericResponseListener
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

class EsServerBasedRorClusterService(nodeName: String,
                                     clusterService: ClusterService,
                                     remoteClusterServiceSupplier: Supplier[Option[RemoteClusterService]],
                                     repositoriesServiceSupplier: Supplier[Option[RepositoriesService]],
                                     nodeClient: NodeClient,
                                     threadPool: ThreadPool)
  extends RorClusterService
    with Logging {

  override def indexOrAliasUuids(indexOrAlias: IndexOrAlias): Set[IndexUuid] = {
    val lookup = clusterService.state.metadata.getIndicesLookup
    lookup.get(indexOrAlias.stringify).getIndices.asScala.map(_.getUUID).toSet
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

  override def allSnapshots: Map[RepositoryName.Full, Set[SnapshotName.Full]] = {
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

  private def extractIndicesAndAliasesFrom(metadata: Metadata) = {
    val indices = metadata.getIndices
    indices
      .keySet().asScala
      .flatMap { index =>
        val indexMetaData = indices.get(index)
        IndexName.Full
          .fromString(indexMetaData.getIndex.getName)
          .map { indexName =>
            val aliases = indexMetaData.getAliases.asSafeMap.keys.flatMap(IndexName.Full.fromString).toSet
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
      .toSet
  }

  private def extractDataStreamsAndAliases(metadata: Metadata): Set[FullLocalDataStreamWithAliases] = {
    val aliasesPerDataStream = aliasesPerDataStreamFrom(metadata)
    backingIndicesPerDataStreamFrom(metadata)
      .map { case (dataStreamName, backingIndices) =>
        FullLocalDataStreamWithAliases(
          dataStreamName = dataStreamName,
          aliasesNames = aliasesPerDataStream.getOrElse(dataStreamName, Set.empty),
          backingIndices = backingIndices
        )
      }
      .toSet
  }

  private def aliasesPerDataStreamFrom(metadata: Metadata): Map[DataStreamName.Full, Set[DataStreamName.Full]] = {
    lazy val mapMonoid: Monoid[Map[DataStreamName.Full, Set[DataStreamName.Full]]] =
      Monoid[Map[DataStreamName.Full, Set[DataStreamName.Full]]]
    val dataStreamAliases = metadata.dataStreamAliases()
    dataStreamAliases
      .keySet().asScala
      .flatMap { aliasName =>
        val dataStreamAlias = dataStreamAliases.get(aliasName)
        val dataStreams: Set[DataStreamName.Full] =
          dataStreamAlias
            .getDataStreams.asScala
            .flatMap { ds =>
              DataStreamName.Full.fromString(ds)
            }
            .toSet

        DataStreamName.Full.fromString(dataStreamAlias.getName)
          .map(alias => (alias, dataStreams))
      }
      .map {
        case (alias, dataStreams) =>
          dataStreams.map(ds => (ds, Set(alias))).toMap
      }
      .foldLeft(Map.empty[DataStreamName.Full, Set[DataStreamName.Full]]) { (acc, aliasesPerDataStream) =>
        mapMonoid.combine(acc, aliasesPerDataStream)
      }
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
            .toSet

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
      .map(_.flatten.toSet)
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
                .toSet

            val dataStreamAliases =
              aliasesPerIndex
                .getOrElse(dataStreamName, Set.empty)
                .map(index => DataStreamName.Full(index.name))

            FullRemoteDataStreamWithAliases(
              clusterName = remoteClusterName,
              dataStreamName = DataStreamName.Full(dataStreamName.name),
              aliasesNames = dataStreamAliases,
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
              .getIndices.asSafeList
              .flatMap { resolvedIndex =>
                toFullRemoteIndexWithAliases(resolvedIndex, remoteClusterName)
              }
          }
    }
  }

  private def resolveRemoteIndicesUsing(client: Client) = {
    import tech.beshu.ror.es.utils.ThreadContextOps._
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
      .toSet
  }

  private def aliasesPerIndexFrom(resolvedAliases: List[ResolvedAlias]) = {
    lazy val mapMonoid: Monoid[Map[IndexName.Full, Set[IndexName.Full]]] =
      Monoid[Map[IndexName.Full, Set[IndexName.Full]]]
    resolvedAliases
      .map { resolvedAlias =>
        resolvedAlias
          .getIndices.asSafeList
          .flatMap(IndexName.Full.fromString)
          .map(index => (index, IndexName.Full.fromString(resolvedAlias.getName).toSet))
          .toMap
      }
      .foldLeft(Map.empty[IndexName.Full, Set[IndexName.Full]]) {
        case (acc, aliasesPerIndex) =>
          mapMonoid.combine(acc, aliasesPerIndex)
      }
  }

  private def indexAttributeFrom(resolvedIndex: ResolvedIndex): IndexAttribute = {
    resolvedIndex
      .getAttributes.toSet
      .find(_.toLowerCase == "CLOSED") match {
      case Some(_) => IndexAttribute.Closed
      case None => IndexAttribute.Opened
    }
  }

  private def snapshotsBy(repositoryName: RepositoryName) = {
    repositoriesServiceSupplier.get() match {
      case Some(repositoriesService) =>
        val repositoryData: RepositoryData = PlainActionFuture.get { fut: PlainActionFuture[RepositoryData] =>
          repositoriesService.getRepositoryData(RepositoryName.toString(repositoryName), fut)
        }
        repositoryData
          .getSnapshotIds.asSafeIterable
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

  private def legacyTemplates(): Set[Template.LegacyTemplate] = {
    val templates = clusterService.state.metadata().templates()
    templates
      .keySet().asScala
      .flatMap { templateNameString =>
        val templateMetaData = templates.get(templateNameString)
        for {
          templateName <- NonEmptyString.unapply(templateNameString).map(TemplateName.apply)
          indexPatterns <- UniqueNonEmptyList.fromIterable(
            templateMetaData.patterns().asScala.flatMap(IndexPattern.fromString)
          )
          aliases = templateMetaData.aliases().asSafeValues.flatMap(a => ClusterIndexName.fromString(a.alias()))
        } yield Template.LegacyTemplate(templateName, indexPatterns, aliases)
      }
      .toSet
  }

  private def indexTemplates(): Set[Template.IndexTemplate] = {
    val templates = clusterService.state.metadata().templatesV2()
    templates
      .keySet().asScala
      .flatMap { templateNameString =>
        val templateMetaData = templates.get(templateNameString)
        for {
          templateName <- NonEmptyString.unapply(templateNameString).map(TemplateName.apply)
          indexPatterns <- UniqueNonEmptyList.fromIterable(
            templateMetaData.indexPatterns().asScala.flatMap(IndexPattern.fromString)
          )
          aliases = templateMetaData.template().asSafeSet
            .flatMap(_.aliases().asSafeMap.values.flatMap(a => ClusterIndexName.fromString(a.alias())).toSet)
        } yield Template.IndexTemplate(templateName, indexPatterns, aliases)
      }
      .toSet
  }

  private def componentTemplates(): Set[Template.ComponentTemplate] = {
    val templates = clusterService.state.metadata().componentTemplates()
    templates
      .keySet().asScala
      .flatMap { templateNameString =>
        val templateMetaData = templates.get(templateNameString)
        for {
          templateName <- NonEmptyString.unapply(templateNameString).map(TemplateName.apply)
          aliases = templateMetaData.template().aliases().asSafeMap.values.flatMap(a => ClusterIndexName.fromString(a.alias())).toSet
        } yield Template.ComponentTemplate(templateName, aliases)
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