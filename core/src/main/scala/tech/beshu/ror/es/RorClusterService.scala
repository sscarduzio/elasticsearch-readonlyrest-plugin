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
import tech.beshu.ror.accesscontrol.domain.IndexName.Remote.ClusterName
import tech.beshu.ror.accesscontrol.matchers.MatcherWithWildcardsScalaAdapter
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.es.RorClusterService._

trait RorClusterService {

  def indexOrAliasUuids(indexOrAlias: IndexOrAlias): Set[IndexUuid]

  def allIndicesAndAliases: Map[IndexName.Local, Set[AliasName]]

  def allRemoteIndicesAndAliases(remoteClusterName: ClusterName): Task[Map[IndexName.Remote.Full, Set[FullRemoteAliasName]]]

  def allTemplates: Set[Template]

  def allSnapshots: Map[RepositoryName.Full, Set[SnapshotName.Full]]

  def verifyDocumentAccessibility(document: Document,
                                  filter: Filter,
                                  id: RequestContext.Id): Task[DocumentAccessibility]

  def verifyDocumentsAccessibilities(documents: NonEmptyList[Document],
                                     filter: Filter,
                                     id: RequestContext.Id): Task[DocumentsAccessibilities]

  def expandIndices(indices: Set[IndexName]): Set[IndexName] = {
    val all: Set[IndexName] = allIndicesAndAliases
      .flatMap { case (indexName, aliases) => aliases + indexName }
      .toSet
    MatcherWithWildcardsScalaAdapter.create(indices).filter(all)
  }
}

object RorClusterService {
  type IndexOrAlias = IndexName
  type Document = DocumentWithIndex
  type DocumentsAccessibilities = Map[DocumentWithIndex, DocumentAccessibility]
  type AliasName = IndexName.Local
  type FullAliasName = IndexName.Local.Full
  type FullRemoteAliasName = IndexName.Remote.Full
  type IndexUuid = String
}