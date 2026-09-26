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

/** A query of an API that names its indices in the query text, which ROR narrows by rewriting that text. */
trait Query[+R] {

  protected def text: String

  def stringify: String = text

  def indices: Set[RequestedIndex[ClusterIndexName]]

}

object Query {

  final case class WithoutIndices(text: String) extends Query[Nothing] {

    override def indices: Set[RequestedIndex[ClusterIndexName]] = allIndices

  }

  final case class Unreadable[+R](text: String, reason: R) extends Query[R] {

    override def indices: Set[RequestedIndex[ClusterIndexName]] = allIndices

  }

  private[es] def narrowedWithoutRewrite[R](
      query: WithoutIndices | Unreadable[R],
      allowedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
  ): Either[R, Query[R]] =
    query match {
      case withoutIndices: WithoutIndices => Right(withoutIndices)
      case unreadable: Unreadable[R]      =>
        Either.cond(allowedIndices.toList.toCovariantSet == unreadable.indices, unreadable, unreadable.reason)
    }

  private val allIndices: Set[RequestedIndex[ClusterIndexName]] =
    Set(RequestedIndex(ClusterIndexName.Local.wildcard, excluded = false))

}
