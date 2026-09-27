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
package tech.beshu.ror.es.query

import cats.data.NonEmptyList
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestedIndex}
import tech.beshu.ror.syntax.*

sealed trait Query[+LOCATED <: LocatedIndexList, +REJECTION] {

  protected def text: String

  def stringify: String = text

  def indices: Set[RequestedIndex[ClusterIndexName]]

}

object Query {

  /** The reader is in a second parameter list, so `equals` ignores it. */
  final case class WithIndices[+LOCATED <: LocatedIndexList] private[query] (
      text: String,
      private[query] val indexLists: NonEmptyList[LOCATED]
  )(
      private[query] val reader: QueryIndicesReader[LOCATED]
  ) extends Query[LOCATED, Nothing] {

    override lazy val indices: Set[RequestedIndex[ClusterIndexName]] =
      indexLists.toList.flatMap(_.requestedIndices.toList).toCovariantSet

  }

  final case class WithoutIndices(text: String) extends Query[Nothing, Nothing] {

    override def indices: Set[RequestedIndex[ClusterIndexName]] = allIndices

  }

  final case class Unreadable[+REJECTION](text: String, reason: REJECTION) extends Query[Nothing, REJECTION] {

    override def indices: Set[RequestedIndex[ClusterIndexName]] = allIndices

  }

  private[query] def readable[LOCATED <: LocatedIndexList](
      text: String,
      indexLists: List[LOCATED],
      reader: QueryIndicesReader[LOCATED]
  ): Query[LOCATED, Nothing] =
    NonEmptyList.fromList(indexLists) match {
      case Some(nonEmptyIndexLists) => WithIndices(text, nonEmptyIndexLists)(reader)
      case None                     => WithoutIndices(text)
    }

  private val allIndices: Set[RequestedIndex[ClusterIndexName]] =
    Set(RequestedIndex(ClusterIndexName.Local.wildcard, excluded = false))

}
