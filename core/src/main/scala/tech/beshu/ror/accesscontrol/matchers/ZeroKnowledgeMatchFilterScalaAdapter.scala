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

import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.accesscontrol.domain.{IndexName, RepositoryName, SnapshotName}
import tech.beshu.ror.accesscontrol.matchers.ZeroKnowledgeMatchFilterScalaAdapter.AlterResult
import tech.beshu.ror.utils.ZeroKnowledgeMatchFilter

import scala.collection.JavaConverters._

class ZeroKnowledgeMatchFilterScalaAdapter {

  def alterIndicesIfNecessary(indices: Set[IndexName], matcher: Matcher[IndexName]): AlterResult[IndexName] = {
    Option(ZeroKnowledgeMatchFilter.alterIndicesIfNecessary(
      indices.map(_.value.value).asJava,
      Matcher.asMatcherWithWildcards(matcher)
    )) match {
      case Some(alteredIndices) => AlterResult.Altered(alteredIndices.asScala.map(str => IndexName(NonEmptyString.unsafeFrom(str))).toSet)
      case None => AlterResult.NotAltered
    }
  }

  def alterRepositoriesIfNecessary(repositories: Set[RepositoryName], matcher: Matcher[RepositoryName]): AlterResult[RepositoryName] = {
    Option(ZeroKnowledgeMatchFilter.alterIndicesIfNecessary(
      repositories.map(_.value.value).asJava,
      Matcher.asMatcherWithWildcards(matcher)
    )) match {
      case Some(alteredRepositories) => AlterResult.Altered(alteredRepositories.asScala.map(str => RepositoryName(NonEmptyString.unsafeFrom(str))).toSet)
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
      Matcher.asMatcherWithWildcards(matcher)
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
