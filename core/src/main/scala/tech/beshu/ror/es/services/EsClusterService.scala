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
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.es.services.EsClusterService.*
import tech.beshu.ror.syntax.*

trait EsClusterService {

  def allRemoteClusterNames(implicit id: RequestId): Set[ClusterName.Full]

  def indexOrAliasUuids(indexOrAlias: IndexOrAlias)
                       (implicit id: RequestId): Set[IndexUuid]

  def allIndicesAndAliases(implicit id: RequestId): Set[FullLocalIndexWithAliases]

  def allRemoteIndicesAndAliases(implicit id: RequestId): Task[Set[FullRemoteIndexWithAliases]]

  def allDataStreamsAndAliases(implicit id: RequestId): Set[FullLocalDataStreamWithAliases]

  def allRemoteDataStreamsAndAliases(implicit id: RequestId): Task[Set[FullRemoteDataStreamWithAliases]]

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
    val all: Set[ClusterIndexName] = allIndicesAndAliases.flatMap(_.all)
    PatternsMatcher.create(indices).filter(all)
  }
}

object EsClusterService {
  type IndexOrAlias = ClusterIndexName
  type Document = DocumentWithIndex
  type DocumentsAccessibility = Map[DocumentWithIndex, DocumentAccessibility]
  type IndexUuid = String
}