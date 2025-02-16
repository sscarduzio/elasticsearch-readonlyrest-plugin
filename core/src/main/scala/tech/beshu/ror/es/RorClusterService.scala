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
package tech.beshu.ror.es

import cats.data.NonEmptyList
import monix.eval.Task
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.DataStreamName.{FullLocalDataStreamWithAliases, FullRemoteDataStreamWithAliases}
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.es.RorClusterService.*
import tech.beshu.ror.syntax.*

trait RorClusterService {

  def indexOrAliasUuids(indexOrAlias: IndexOrAlias): Set[IndexUuid]

  def allIndicesAndAliases: Set[FullLocalIndexWithAliases]

  def allRemoteIndicesAndAliases: Task[Set[FullRemoteIndexWithAliases]]

  def allDataStreamsAndAliases: Set[FullLocalDataStreamWithAliases]

  def allRemoteDataStreamsAndAliases: Task[Set[FullRemoteDataStreamWithAliases]]

  def allTemplates: Set[Template]

  def allSnapshots: Map[RepositoryName.Full, Task[Set[SnapshotName.Full]]]

  def verifyDocumentAccessibility(document: Document,
                                  filter: Filter,
                                  id: RequestContext.Id): Task[DocumentAccessibility]

  def verifyDocumentsAccessibilities(documents: NonEmptyList[Document],
                                     filter: Filter,
                                     id: RequestContext.Id): Task[DocumentsAccessibilities]

  def expandLocalIndices(indices: Set[ClusterIndexName]): Set[ClusterIndexName] = {
    val all: Set[ClusterIndexName] = allIndicesAndAliases.flatMap(_.all)
    PatternsMatcher.create(indices).filter(all)
  }
}

object RorClusterService {
  type IndexOrAlias = ClusterIndexName
  type Document = DocumentWithIndex
  type DocumentsAccessibilities = Map[DocumentWithIndex, DocumentAccessibility]
  type IndexUuid = String
}