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
import tech.beshu.ror.es.query.Query.{Unreadable, WithIndices, WithoutIndices}
import tech.beshu.ror.syntax.*

private[query] trait QueryNarrowing[LOCATED <: LocatedIndexList, REJECTION] {

  extension (query: Query[LOCATED, REJECTION]) {

    def narrowedTo(
        filteredRequestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
    )(
        implicit requestId: RequestId
    ): Either[REJECTION, Query[LOCATED, REJECTION]] = {
      val unchanged = filteredRequestedIndices.toList.toCovariantSet == query.indices
      query match {
        case withIndices: WithIndices[LOCATED] =>
          if (unchanged) Right(withIndices) else rewritten(withIndices, filteredRequestedIndices)
        case withoutIndices: WithoutIndices =>
          Right(withoutIndices)
        case unreadable: Unreadable[REJECTION] =>
          Either.cond(unchanged, unreadable, unreadable.reason)
      }
    }

  }

  protected def rewritten(
      query: WithIndices[LOCATED],
      filteredRequestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
  )(
      implicit requestId: RequestId
  ): Either[REJECTION, Query[LOCATED, REJECTION]]

}
