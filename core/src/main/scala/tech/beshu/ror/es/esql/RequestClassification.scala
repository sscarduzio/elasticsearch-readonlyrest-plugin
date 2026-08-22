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
import tech.beshu.ror.syntax.*

sealed trait RequestClassification

object RequestClassification {

  final case class IndicesRelated(indexLists: NonEmptyList[LocatedIndexList]) extends RequestClassification {
    lazy val requestedIndices: Set[RequestedIndex[ClusterIndexName]] =
      LocatedIndexList.requestedIndicesOf(indexLists)
  }

  case object NonIndicesRelated extends RequestClassification
}

/** Why ROR could not read a query into the index lists it names. */
sealed trait ClassificationError

object ClassificationError {

  /** ES rejects such a query on its own, so ROR can let it through rather than answer for a syntax error. */
  final case class NotParsable(cause: Throwable) extends ClassificationError

  final case class CannotReadIndexList(failure: IndexListReadingFailure) extends ClassificationError
}
