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
package tech.beshu.ror.es.esql

import cats.data.NonEmptyList
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestedIndex}
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.es.esql.Query.TextSpan
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*

/** An index list found in the query text, so the indices it names can be replaced with the ones the ACL allowed. */
sealed trait LocatedIndexList {
  def span: TextSpan
  def requestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
}

object LocatedIndexList {

  final case class SourceCommandIndices private (
      span: TextSpan,
      requestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
  ) extends LocatedIndexList {

    lazy val writtenPattern: PatternsMatcher[ClusterIndexName] =
      PatternsMatcher.create(requestedIndices.includedOnly)
  }

  object SourceCommandIndices {
    def parse(span: TextSpan, read: IndexListRead): Option[SourceCommandIndices] =
      requestedIndicesIn(read).map(SourceCommandIndices(span, _))
  }

  final case class LookupJoinTarget private (span: TextSpan, index: ClusterIndexName) extends LocatedIndexList {
    override def requestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]] =
      NonEmptyList.one(RequestedIndex(index, excluded = false))
  }

  object LookupJoinTarget {

    def parse(span: TextSpan, read: IndexListRead): Option[LookupJoinTarget] =
      requestedIndicesIn(read).collect {
        case NonEmptyList(onlyOne, Nil) if !onlyOne.excluded => LookupJoinTarget(span, onlyOne.name)
      }

  }

  def requestedIndicesOf(indexLists: NonEmptyList[LocatedIndexList]): Set[RequestedIndex[ClusterIndexName]] =
    indexLists.toList.flatMap(_.requestedIndices.toList).toCovariantSet

  private def requestedIndicesIn(read: IndexListRead): Option[NonEmptyList[RequestedIndex[ClusterIndexName]]] =
    NonEmptyList.fromList(
      read.indexList.split(',').asSafeList.filter(_.nonEmpty).flatMap(RequestedIndex.fromString)
    )

}
