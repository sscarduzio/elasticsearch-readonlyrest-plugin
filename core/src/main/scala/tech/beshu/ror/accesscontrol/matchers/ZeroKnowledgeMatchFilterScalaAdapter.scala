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
package tech.beshu.ror.accesscontrol.matchers

import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RepositoryName, SnapshotName}
import tech.beshu.ror.accesscontrol.matchers.ZeroKnowledgeMatchFilterScalaAdapter.AlterResult
import tech.beshu.ror.utils.{JavaStringMatcher, ZeroKnowledgeMatchFilter}

import scala.jdk.CollectionConverters._

class ZeroKnowledgeMatchFilterScalaAdapter {

  def alterIndicesIfNecessary(indices: Set[ClusterIndexName], matcher: Matcher[ClusterIndexName]): AlterResult[ClusterIndexName] = {
    Option(ZeroKnowledgeMatchFilter.alterIndicesIfNecessary(
      indices.map(_.stringify).asJava,
      new JavaStringMatcher(matcher)
    )) match {
      case Some(alteredIndices) => AlterResult.Altered(alteredIndices.asScala.flatMap(ClusterIndexName.fromString).toSet)
      case None => AlterResult.NotAltered
    }
  }

  def alterRepositoriesIfNecessary(repositories: Set[RepositoryName], matcher: Matcher[RepositoryName]): AlterResult[RepositoryName] = {
    Option(ZeroKnowledgeMatchFilter.alterIndicesIfNecessary(
      repositories
        .collect {
          case RepositoryName.Pattern(v) => v.value
          case RepositoryName.Full(v) => v.value
        }
        .asJava,
      new JavaStringMatcher(matcher)
    )) match {
      case Some(alteredRepositories) => AlterResult.Altered(alteredRepositories.asScala.flatMap(RepositoryName.from).toSet)
      case None => AlterResult.NotAltered
    }
  }

  def alterSnapshotsIfNecessary(snapshots: Set[SnapshotName], matcher: Matcher[SnapshotName]): AlterResult[SnapshotName] = {
    Option(ZeroKnowledgeMatchFilter.alterIndicesIfNecessary(
      snapshots
        .collect {
          case SnapshotName.Pattern(v) => v.value
          case SnapshotName.Full(v) => v.value
        }
        .asJava,
      new JavaStringMatcher(matcher)
    )) match {
      case Some(alteredSnapshots) => AlterResult.Altered(alteredSnapshots.asScala.flatMap(SnapshotName.from).toSet)
      case None => AlterResult.NotAltered
    }
  }
}

object ZeroKnowledgeMatchFilterScalaAdapter {
  sealed trait AlterResult[+T]
  object AlterResult {
    case object NotAltered extends AlterResult[Nothing]
    final case class Altered[T](values: Set[T]) extends AlterResult[T]
  }
}
