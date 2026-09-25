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
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestId, RequestedIndex}
import tech.beshu.ror.syntax.*

/** A query of an API that names its indices in the query text, which ROR narrows by rewriting that text. */
trait Query[+R] {

  protected def text: String

  def stringify: String = text

  def indices: Set[RequestedIndex[ClusterIndexName]]

  def narrowedTo(
      allowedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
  )(
      implicit requestId: RequestId
  ): Either[R, Query[R]]

}

object Query {

  final case class WithoutIndices(text: String) extends Query[Nothing] {

    override def indices: Set[RequestedIndex[ClusterIndexName]] = allIndices

    override def narrowedTo(
        allowedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
    )(
        implicit requestId: RequestId
    ): Either[Nothing, Query[Nothing]] = Right(this)

  }

  final case class Unreadable[+R](text: String, reason: R) extends Query[R] {

    override def indices: Set[RequestedIndex[ClusterIndexName]] = allIndices

    override def narrowedTo(
        allowedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
    )(
        implicit requestId: RequestId
    ): Either[R, Query[R]] =
      Either.cond(allowedIndices.toList.toCovariantSet == indices, this, reason)

  }

  private val allIndices: Set[RequestedIndex[ClusterIndexName]] =
    Set(RequestedIndex(ClusterIndexName.Local.wildcard, excluded = false))

}
