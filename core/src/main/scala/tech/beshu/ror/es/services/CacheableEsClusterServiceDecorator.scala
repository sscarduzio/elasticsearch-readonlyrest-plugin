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
import tech.beshu.ror.accesscontrol.domain.DataStreamName.{FullLocalDataStreamWithAliases, FullRemoteDataStreamWithAliases}
import tech.beshu.ror.accesscontrol.utils.{AsyncCacheableAction, SyncCacheableAction}
import tech.beshu.ror.es.services.EsClusterService.*
import tech.beshu.ror.syntax.*

class CacheableEsClusterServiceDecorator(underlying: EsClusterService) extends EsClusterService {

  private val cacheableAllRemoteClusterNames = new SyncCacheableAction[Unit, Set[ClusterName.Full]](
    action = (_, id) => underlying.allRemoteClusterNames(id)
  )

  private val cacheableAllIndicesAndAliases = new SyncCacheableAction[Unit, Set[FullLocalIndexWithAliases]](
    action = (_, id) => underlying.allIndicesAndAliases(id)
  )

  private val cacheableAllDataStreamsAndAliases = new SyncCacheableAction[Unit, Set[FullLocalDataStreamWithAliases]](
    action = (_, id) => underlying.allDataStreamsAndAliases(id)
  )

  private val cacheableLegacyTemplates = new SyncCacheableAction[Unit, Set[Template.LegacyTemplate]](
    action = (_, id) => underlying.legacyTemplates(id)
  )

  private val cacheableIndexTemplates = new SyncCacheableAction[Unit, Set[Template.IndexTemplate]](
    action = (_, id) => underlying.indexTemplates(id)
  )

  private val cacheableComponentTemplates = new SyncCacheableAction[Unit, Set[Template.ComponentTemplate]](
    action = (_, id) => underlying.componentTemplates(id)
  )

  private val cacheableIndexOrAliasUuids = new SyncCacheableAction[IndexOrAlias, Set[IndexUuid]](
    action = (indexOrAlias, id) => underlying.indexOrAliasUuids(indexOrAlias)(id)
  )

  private val cacheableAllSnapshots = new SyncCacheableAction[Unit, Map[RepositoryName.Full, Task[Set[SnapshotName.Full]]]](
    action = (_, id) => underlying.allSnapshots(id)
  )

  private val cacheableAllRemoteIndicesAndAliases = new AsyncCacheableAction[Unit, Set[FullRemoteIndexWithAliases]](
    action = (_, requestId) => underlying.allRemoteIndicesAndAliases(requestId)
  )

  private val cacheableAllRemoteDataStreamsAndAliases = new AsyncCacheableAction[Unit, Set[FullRemoteDataStreamWithAliases]](
    action = (_, requestId) => underlying.allRemoteDataStreamsAndAliases(requestId)
  )

  private val cacheableSnapshotIndices = new AsyncCacheableAction[(RepositoryName.Full, SnapshotName.Full), Set[ClusterIndexName]](
    action = {
      case ((repoName, snapName), requestId) => underlying.snapshotIndices(repoName, snapName)(requestId)
    }
  )

  private val cacheableVerifyDocumentAccessibility = new AsyncCacheableAction[(Document, Filter), DocumentAccessibility](
    action = {
      case ((document, filter), requestId) => underlying.verifyDocumentAccessibility(document, filter)(requestId)
    }
  )

  private val cacheableVerifyDocumentsAccessibility = new AsyncCacheableAction[(NonEmptyList[Document], Filter), DocumentsAccessibility](
    action = {
      case ((documents, filter), requestId) => underlying.verifyDocumentsAccessibility(documents, filter)(requestId)
    }
  )

  override def allRemoteClusterNames(implicit id: RequestId): Set[ClusterName.Full] =
    cacheableAllRemoteClusterNames.call(())

  override def indexOrAliasUuids(indexOrAlias: IndexOrAlias)
                                (implicit id: RequestId): Set[IndexUuid] =
    cacheableIndexOrAliasUuids.call(indexOrAlias)

  override def allIndicesAndAliases(implicit id: RequestId): Set[FullLocalIndexWithAliases] =
    cacheableAllIndicesAndAliases.call(())

  override def allRemoteIndicesAndAliases(implicit id: RequestId): Task[Set[FullRemoteIndexWithAliases]] =
    cacheableAllRemoteIndicesAndAliases.call(())

  override def allDataStreamsAndAliases(implicit id: RequestId): Set[FullLocalDataStreamWithAliases] =
    cacheableAllDataStreamsAndAliases.call(())

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
