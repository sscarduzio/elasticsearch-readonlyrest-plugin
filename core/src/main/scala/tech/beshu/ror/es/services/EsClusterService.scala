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
import monix.eval.Task
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote.ClusterName
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.{Local as LocalIndexName, Remote as RemoteIndexName}
import tech.beshu.ror.accesscontrol.domain.DataStreamName.{FullLocalDataStreamWithAliases, FullRemoteDataStreamWithAliases}
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.utils.{AsyncCacheableAction, SyncCacheableAction}
import tech.beshu.ror.es.services.EsClusterService.{LocalDataStreamsSnapshot, LocalIndicesSnapshot, *}
import tech.beshu.ror.syntax.*

import scala.collection.mutable

trait EsClusterService {

  def remoteClustersConfigured(implicit id: RequestId): Boolean

  def allRemoteClusterNames(implicit id: RequestId): Set[ClusterName.Full]

  def indexOrAliasUuids(indexOrAlias: IndexOrAlias)
                       (implicit id: RequestId): Set[IndexUuid]

  def indicesPerAliasMap(filteredBy: Set[IndexAttribute])
                        (implicit requestId: RequestId): Task[Map[LocalIndexName, Set[LocalIndexName]]] =
    Task.delay(localIndicesSnapshot.indicesPerAliasMapFor(filteredBy))

  def allIndicesAndAliases(implicit id: RequestId): Set[FullLocalIndexWithAliases]

  def remoteIndicesPerAliasMap(filteredBy: Set[IndexAttribute])
                              (implicit id: RequestId): Task[Map[RemoteIndexName, Set[RemoteIndexName]]] = {
    remoteIndices(filteredBy)
      .map { indices =>
        val collected = mutable.HashMap.empty[RemoteIndexName, mutable.Builder[RemoteIndexName, Set[RemoteIndexName]]]
        indices.foreach { remoteIndexWithAliases =>
          remoteIndexWithAliases.aliasesNames.foreach { alias =>
            collected.getOrElseUpdate(
              RemoteIndexName(alias, remoteIndexWithAliases.clusterName),
              Set.newBuilder[RemoteIndexName]
            ) += RemoteIndexName(remoteIndexWithAliases.indexName, remoteIndexWithAliases.clusterName)
          }
        }
        buildAliasMap(collected)
      }
  }

  private def remoteIndices(filteredBy: Set[IndexAttribute])
                           (implicit id: RequestId) = {
    if (filteredBy.nonEmpty) {
      allRemoteIndicesAndAliases.map(_.filter(i => filteredBy.contains(i.attribute)))
    } else {
      allRemoteIndicesAndAliases
    }
  }

  def allRemoteIndicesAndAliases(implicit id: RequestId): Task[Set[FullRemoteIndexWithAliases]]

  def dataStreamsPerAliasMap(filteredBy: Set[IndexAttribute])
                            (implicit requestId: RequestId): Task[Map[LocalIndexName, Set[LocalIndexName]]] =
    Task.delay(localDataStreamsSnapshot.dataStreamsPerAliasMapFor(filteredBy))

  def backingIndicesPerDataStreamMap(filteredBy: Set[IndexAttribute])
                                    (implicit requestId: RequestId): Task[Map[LocalIndexName, Set[LocalIndexName]]] =
    Task.delay(localDataStreamsSnapshot.backingIndicesPerDataStreamMapFor(filteredBy))

  def allDataStreamsAndAliases(implicit id: RequestId): Set[FullLocalDataStreamWithAliases]

  def allRemoteDataStreamsAndAliases(implicit id: RequestId): Task[Set[FullRemoteDataStreamWithAliases]]

  def remoteDataStreamsPerAliasMap(filteredBy: Set[IndexAttribute])
                                  (implicit id: RequestId): Task[Map[RemoteIndexName, Set[RemoteIndexName]]] = {
    remoteDataStreams(filteredBy)
      .map { dataStreams =>
        val collected = mutable.HashMap.empty[RemoteIndexName, mutable.Builder[RemoteIndexName, Set[RemoteIndexName]]]
        dataStreams.foreach { fullRemoteDataStream =>
          fullRemoteDataStream.aliases.foreach { alias =>
            collected.getOrElseUpdate(alias, Set.newBuilder[RemoteIndexName]) += fullRemoteDataStream.dataStream
          }
        }
        buildAliasMap(collected)
      }
  }

  def remoteBackingIndicesPerDataStreamMap(filteredBy: Set[IndexAttribute])
                                          (implicit id: RequestId): Task[Map[RemoteIndexName, Set[RemoteIndexName]]] = {
    remoteDataStreams(filteredBy)
      .map { dataStreams =>
        val collected = mutable.HashMap.empty[RemoteIndexName, mutable.Builder[RemoteIndexName, Set[RemoteIndexName]]]
        dataStreams.foreach { fullRemoteDataStream =>
          fullRemoteDataStream.indices.foreach { index =>
            collected.getOrElseUpdate(fullRemoteDataStream.dataStream, Set.newBuilder[RemoteIndexName]) += index
          }
        }
        buildAliasMap(collected)
      }
  }

  private def remoteDataStreams(filteredBy: Set[IndexAttribute])
                               (implicit id: RequestId) = {
    if (filteredBy.nonEmpty) {
      allRemoteDataStreamsAndAliases.map(_.filter(ds => filteredBy.contains(ds.attribute)))
    } else {
      allRemoteDataStreamsAndAliases
    }
  }

  def localIndicesSnapshot(implicit id: RequestId): LocalIndicesSnapshot = new LocalIndicesSnapshot(allIndicesAndAliases)

  def localDataStreamsSnapshot(implicit id: RequestId): LocalDataStreamsSnapshot = new LocalDataStreamsSnapshot(allDataStreamsAndAliases)

  final def allTemplates(implicit id: RequestId): Set[Template] = {
    legacyTemplates ++ indexTemplates ++ componentTemplates
  }

  def legacyTemplates(implicit id: RequestId): Set[Template.LegacyTemplate]

  def indexTemplates(implicit id: RequestId): Set[Template.IndexTemplate]

  def componentTemplates(implicit id: RequestId): Set[Template.ComponentTemplate]

  def allSnapshots(implicit id: RequestId): Map[RepositoryName.Full, Task[Set[SnapshotName.Full]]]

  def snapshotIndices(repositoryName: RepositoryName.Full,
                      snapshotName: SnapshotName.Full)
                     (implicit id: RequestId): Task[Set[ClusterIndexName]]

  def verifyDocumentAccessibility(document: Document,
                                  filter: Filter)
                                 (implicit id: RequestId): Task[DocumentAccessibility]

  def verifyDocumentsAccessibility(documents: NonEmptyList[Document],
                                   filter: Filter)
                                  (implicit id: RequestId): Task[DocumentsAccessibility]

  def expandLocalIndices(indices: Set[ClusterIndexName])
                        (implicit id: RequestId): Set[ClusterIndexName] = {
    PatternsMatcher.create(indices).filter(localIndicesSnapshot.indicesAndAliases)
  }
}

object EsClusterService {
  type IndexOrAlias = ClusterIndexName
  type Document = DocumentWithIndex
  type DocumentsAccessibility = Map[DocumentWithIndex, DocumentAccessibility]
  type IndexUuid = String

  private val allIndexAttributes: Set[IndexAttribute] = Set(IndexAttribute.Opened, IndexAttribute.Closed)

  private def usesAllIndexAttributes(filteredBy: Set[IndexAttribute]): Boolean =
    filteredBy.isEmpty || filteredBy == allIndexAttributes

  private def indicesPerAliasMapFrom(indices: Iterable[FullLocalIndexWithAliases]): Map[LocalIndexName, Set[LocalIndexName]] = {
    val collected = mutable.HashMap.empty[LocalIndexName, mutable.Builder[LocalIndexName, Set[LocalIndexName]]]
    indices.foreach { indexWithAliases =>
      indexWithAliases.aliases.foreach { alias =>
        collected.getOrElseUpdate(alias, Set.newBuilder[LocalIndexName]) += indexWithAliases.index
      }
    }
    buildAliasMap(collected)
  }

  private def dataStreamsPerAliasMapFrom(dataStreams: Iterable[FullLocalDataStreamWithAliases]): Map[LocalIndexName, Set[LocalIndexName]] = {
    val collected = mutable.HashMap.empty[LocalIndexName, mutable.Builder[LocalIndexName, Set[LocalIndexName]]]
    dataStreams.foreach { dataStreamWithAliases =>
      dataStreamWithAliases.aliases.foreach { alias =>
        collected.getOrElseUpdate(alias, Set.newBuilder[LocalIndexName]) += dataStreamWithAliases.dataStream
      }
    }
    buildAliasMap(collected)
  }

  private def backingIndicesPerDataStreamMapFrom(dataStreams: Iterable[FullLocalDataStreamWithAliases]): Map[LocalIndexName, Set[LocalIndexName]] = {
    val collected = mutable.HashMap.empty[LocalIndexName, mutable.Builder[LocalIndexName, Set[LocalIndexName]]]
    dataStreams.foreach { fullDataStream =>
      fullDataStream.indices.foreach { index =>
        collected.getOrElseUpdate(fullDataStream.dataStream, Set.newBuilder[LocalIndexName]) += index
      }
    }
    buildAliasMap(collected)
  }

  private def buildAliasMap[K, V](collected: mutable.HashMap[K, mutable.Builder[V, Set[V]]]): Map[K, Set[V]] = {
    val mapBuilder = Map.newBuilder[K, Set[V]]
    mapBuilder.sizeHint(collected.size)
    collected.foreach { case (k, setBuilder) =>
      mapBuilder += (k -> setBuilder.result())
    }
    mapBuilder.result()
  }

  final class LocalIndicesSnapshot(val raw: Set[FullLocalIndexWithAliases]) {
    lazy val indicesAndAliases: Set[LocalIndexName] = raw.flatMap(_.all)
    lazy val indices: Set[LocalIndexName] = raw.map(_.index)
    lazy val aliases: Set[LocalIndexName] = raw.flatMap(_.aliases)

    private lazy val opened = raw.filter(_.attribute == IndexAttribute.Opened)
    private lazy val closed = raw.filter(_.attribute == IndexAttribute.Closed)

    private lazy val openedIndicesAndAliases = opened.flatMap(_.all)
    private lazy val closedIndicesAndAliases = closed.flatMap(_.all)
    private lazy val openedIndices = opened.map(_.index)
    private lazy val closedIndices = closed.map(_.index)

    private lazy val fullIndicesPerAliasMap = indicesPerAliasMapFrom(raw)
    private lazy val openedIndicesPerAliasMap = indicesPerAliasMapFrom(opened)
    private lazy val closedIndicesPerAliasMap = indicesPerAliasMapFrom(closed)

    def indicesAndAliasesFor(filteredBy: Set[IndexAttribute]): Set[LocalIndexName] = {
      if (usesAllIndexAttributes(filteredBy)) indicesAndAliases
      else if (filteredBy.contains(IndexAttribute.Opened)) openedIndicesAndAliases
      else if (filteredBy.contains(IndexAttribute.Closed)) closedIndicesAndAliases
      else Set.empty
    }

    def indicesFor(filteredBy: Set[IndexAttribute]): Set[LocalIndexName] = {
      if (usesAllIndexAttributes(filteredBy)) indices
      else if (filteredBy.contains(IndexAttribute.Opened)) openedIndices
      else if (filteredBy.contains(IndexAttribute.Closed)) closedIndices
      else Set.empty
    }

    def indicesPerAliasMapFor(filteredBy: Set[IndexAttribute]): Map[LocalIndexName, Set[LocalIndexName]] = {
      if (usesAllIndexAttributes(filteredBy)) fullIndicesPerAliasMap
      else if (filteredBy.contains(IndexAttribute.Opened)) openedIndicesPerAliasMap
      else if (filteredBy.contains(IndexAttribute.Closed)) closedIndicesPerAliasMap
      else Map.empty
    }
  }

  final class LocalDataStreamsSnapshot(val raw: Set[FullLocalDataStreamWithAliases]) {
    lazy val dataStreamsAndAliases: Set[LocalIndexName] = raw.flatMap(_.all)
    lazy val dataStreams: Set[LocalIndexName] = raw.map(_.dataStream)
    lazy val dataStreamAliases: Set[LocalIndexName] = raw.flatMap(_.aliases)

    private lazy val fullDataStreamsPerAliasMap = dataStreamsPerAliasMapFrom(raw)
    private lazy val fullBackingIndicesPerDataStreamMap = backingIndicesPerDataStreamMapFrom(raw)

    def dataStreamsAndAliasesFor(filteredBy: Set[IndexAttribute]): Set[LocalIndexName] = {
      if (filteredBy.isEmpty || filteredBy.contains(IndexAttribute.Opened)) dataStreamsAndAliases
      else Set.empty
    }

    def dataStreamsFor(filteredBy: Set[IndexAttribute]): Set[LocalIndexName] = {
      if (filteredBy.isEmpty || filteredBy.contains(IndexAttribute.Opened)) dataStreams
      else Set.empty
    }

    def dataStreamsPerAliasMapFor(filteredBy: Set[IndexAttribute]): Map[LocalIndexName, Set[LocalIndexName]] = {
      if (filteredBy.isEmpty || filteredBy.contains(IndexAttribute.Opened)) fullDataStreamsPerAliasMap
      else Map.empty
    }

    def backingIndicesPerDataStreamMapFor(filteredBy: Set[IndexAttribute]): Map[LocalIndexName, Set[LocalIndexName]] = {
      if (filteredBy.isEmpty || filteredBy.contains(IndexAttribute.Opened)) fullBackingIndicesPerDataStreamMap
      else Map.empty
    }
  }
}

class CacheableEsClusterServiceDecorator(underlying: EsClusterService) extends EsClusterService {

  private lazy val cacheableRemoteClustersConfigured = new SyncCacheableAction[Unit, Boolean](
    action = (_, id) => underlying.remoteClustersConfigured(id)
  )

  private lazy val cacheableAllRemoteClusterNames = new SyncCacheableAction[Unit, Set[ClusterName.Full]](
    action = (_, id) => underlying.allRemoteClusterNames(id)
  )

  private lazy val cacheableAllIndicesAndAliases = new SyncCacheableAction[Unit, Set[FullLocalIndexWithAliases]](
    action = (_, id) => underlying.allIndicesAndAliases(id)
  )

  private lazy val cacheableAllDataStreamsAndAliases = new SyncCacheableAction[Unit, Set[FullLocalDataStreamWithAliases]](
    action = (_, id) => underlying.allDataStreamsAndAliases(id)
  )

  private lazy val cacheableLegacyTemplates = new SyncCacheableAction[Unit, Set[Template.LegacyTemplate]](
    action = (_, id) => underlying.legacyTemplates(id)
  )

  private lazy val cacheableIndexTemplates = new SyncCacheableAction[Unit, Set[Template.IndexTemplate]](
    action = (_, id) => underlying.indexTemplates(id)
  )

  private lazy val cacheableComponentTemplates = new SyncCacheableAction[Unit, Set[Template.ComponentTemplate]](
    action = (_, id) => underlying.componentTemplates(id)
  )

  private lazy val cacheableIndexOrAliasUuids = new SyncCacheableAction[IndexOrAlias, Set[IndexUuid]](
    action = (indexOrAlias, id) => underlying.indexOrAliasUuids(indexOrAlias)(id)
  )

  private lazy val cacheableLocalIndicesSnapshot = new SyncCacheableAction[Unit, LocalIndicesSnapshot](
    action = (_, id) => underlying.localIndicesSnapshot(id)
  )

  private lazy val cacheableLocalDataStreamsSnapshot = new SyncCacheableAction[Unit, LocalDataStreamsSnapshot](
    action = (_, id) => underlying.localDataStreamsSnapshot(id)
  )

  private lazy val cacheableAllSnapshots = new SyncCacheableAction[Unit, Map[RepositoryName.Full, Task[Set[SnapshotName.Full]]]](
    action = (_, id) => underlying.allSnapshots(id)
  )

  private lazy val cacheableAllRemoteIndicesAndAliases = new AsyncCacheableAction[Unit, Set[FullRemoteIndexWithAliases]](
    action = (_, requestId) => underlying.allRemoteIndicesAndAliases(requestId)
  )

  private lazy val cacheableAllRemoteDataStreamsAndAliases = new AsyncCacheableAction[Unit, Set[FullRemoteDataStreamWithAliases]](
    action = (_, requestId) => underlying.allRemoteDataStreamsAndAliases(requestId)
  )

  private lazy val cacheableSnapshotIndices = new AsyncCacheableAction[(RepositoryName.Full, SnapshotName.Full), Set[ClusterIndexName]](
    action = {
      case ((repoName, snapName), requestId) => underlying.snapshotIndices(repoName, snapName)(requestId)
    }
  )

  private lazy val cacheableVerifyDocumentAccessibility = new AsyncCacheableAction[(Document, Filter), DocumentAccessibility](
    action = {
      case ((document, filter), requestId) => underlying.verifyDocumentAccessibility(document, filter)(requestId)
    }
  )

  private lazy val cacheableVerifyDocumentsAccessibility = new AsyncCacheableAction[(NonEmptyList[Document], Filter), DocumentsAccessibility](
    action = {
      case ((documents, filter), requestId) => underlying.verifyDocumentsAccessibility(documents, filter)(requestId)
    }
  )

  override def remoteClustersConfigured(implicit id: RequestId): Boolean =
    cacheableRemoteClustersConfigured.call(())

  override def allRemoteClusterNames(implicit id: RequestId): Set[ClusterName.Full] =
    cacheableAllRemoteClusterNames.call(())

  override def indexOrAliasUuids(indexOrAlias: IndexOrAlias)
                                (implicit id: RequestId): Set[IndexUuid] =
    cacheableIndexOrAliasUuids.call(indexOrAlias)

  override def allIndicesAndAliases(implicit id: RequestId): Set[FullLocalIndexWithAliases] =
    cacheableAllIndicesAndAliases.call(())

  private lazy val cacheableIndicesPerAliasMap = new AsyncCacheableAction[Set[IndexAttribute], Map[LocalIndexName, Set[LocalIndexName]]](
    action = (filteredBy, id) => underlying.indicesPerAliasMap(filteredBy)(id)
  )

  private lazy val cacheableDataStreamsPerAliasMap = new AsyncCacheableAction[Set[IndexAttribute], Map[LocalIndexName, Set[LocalIndexName]]](
    action = (filteredBy, id) => underlying.dataStreamsPerAliasMap(filteredBy)(id)
  )

  private lazy val cacheableBackingIndicesPerDataStreamMap = new AsyncCacheableAction[Set[IndexAttribute], Map[LocalIndexName, Set[LocalIndexName]]](
    action = (filteredBy, id) => underlying.backingIndicesPerDataStreamMap(filteredBy)(id)
  )

  override def indicesPerAliasMap(filteredBy: Set[IndexAttribute])
                                 (implicit requestId: RequestId): Task[Map[LocalIndexName, Set[LocalIndexName]]] =
    cacheableIndicesPerAliasMap.call(filteredBy)

  override def dataStreamsPerAliasMap(filteredBy: Set[IndexAttribute])
                                     (implicit requestId: RequestId): Task[Map[LocalIndexName, Set[LocalIndexName]]] =
    cacheableDataStreamsPerAliasMap.call(filteredBy)

  override def backingIndicesPerDataStreamMap(filteredBy: Set[IndexAttribute])
                                             (implicit requestId: RequestId): Task[Map[LocalIndexName, Set[LocalIndexName]]] =
    cacheableBackingIndicesPerDataStreamMap.call(filteredBy)

  override def allRemoteIndicesAndAliases(implicit id: RequestId): Task[Set[FullRemoteIndexWithAliases]] =
    cacheableAllRemoteIndicesAndAliases.call(())

  private lazy val cacheableRemoteIndicesPerAliasMap = new AsyncCacheableAction[Set[IndexAttribute], Map[RemoteIndexName, Set[RemoteIndexName]]](
    action = (filteredBy, id) => underlying.remoteIndicesPerAliasMap(filteredBy)(id)
  )

  override def remoteIndicesPerAliasMap(filteredBy: Set[IndexAttribute])
                                       (implicit id: RequestId): Task[Map[RemoteIndexName, Set[RemoteIndexName]]] =
    cacheableRemoteIndicesPerAliasMap.call(filteredBy)

  private lazy val cacheableRemoteDataStreamsPerAliasMap = new AsyncCacheableAction[Set[IndexAttribute], Map[RemoteIndexName, Set[RemoteIndexName]]](
    action = (filteredBy, id) => underlying.remoteDataStreamsPerAliasMap(filteredBy)(id)
  )

  private lazy val cacheableRemoteBackingIndicesPerDataStreamMap = new AsyncCacheableAction[Set[IndexAttribute], Map[RemoteIndexName, Set[RemoteIndexName]]](
    action = (filteredBy, id) => underlying.remoteBackingIndicesPerDataStreamMap(filteredBy)(id)
  )

  override def remoteDataStreamsPerAliasMap(filteredBy: Set[IndexAttribute])
                                           (implicit id: RequestId): Task[Map[RemoteIndexName, Set[RemoteIndexName]]] =
    cacheableRemoteDataStreamsPerAliasMap.call(filteredBy)

  override def remoteBackingIndicesPerDataStreamMap(filteredBy: Set[IndexAttribute])
                                                   (implicit id: RequestId): Task[Map[RemoteIndexName, Set[RemoteIndexName]]] =
    cacheableRemoteBackingIndicesPerDataStreamMap.call(filteredBy)

  override def allDataStreamsAndAliases(implicit id: RequestId): Set[FullLocalDataStreamWithAliases] =
    cacheableAllDataStreamsAndAliases.call(())

  override def localIndicesSnapshot(implicit id: RequestId): LocalIndicesSnapshot =
    cacheableLocalIndicesSnapshot.call(())

  override def localDataStreamsSnapshot(implicit id: RequestId): LocalDataStreamsSnapshot =
    cacheableLocalDataStreamsSnapshot.call(())

  override def allRemoteDataStreamsAndAliases(implicit id: RequestId): Task[Set[FullRemoteDataStreamWithAliases]] =
    cacheableAllRemoteDataStreamsAndAliases.call(())

  override def legacyTemplates(implicit id: RequestId): Set[Template.LegacyTemplate] =
    cacheableLegacyTemplates.call(())

  override def indexTemplates(implicit id: RequestId): Set[Template.IndexTemplate] =
    cacheableIndexTemplates.call(())

  override def componentTemplates(implicit id: RequestId): Set[Template.ComponentTemplate] =
    cacheableComponentTemplates.call(())

  override def allSnapshots(implicit id: RequestId): Map[RepositoryName.Full, Task[Set[SnapshotName.Full]]] =
    cacheableAllSnapshots.call(())

  override def snapshotIndices(repositoryName: RepositoryName.Full,
                               snapshotName: SnapshotName.Full)
                              (implicit id: RequestId): Task[Set[ClusterIndexName]] =
    cacheableSnapshotIndices.call((repositoryName, snapshotName))

  override def verifyDocumentAccessibility(document: Document,
                                           filter: Filter)
                                          (implicit id: RequestId): Task[DocumentAccessibility] =
    cacheableVerifyDocumentAccessibility.call((document, filter))

  override def verifyDocumentsAccessibility(documents: NonEmptyList[Document],
                                            filter: Filter)
                                           (implicit id: RequestId): Task[DocumentsAccessibility] =
    cacheableVerifyDocumentsAccessibility.call((documents, filter))

}
