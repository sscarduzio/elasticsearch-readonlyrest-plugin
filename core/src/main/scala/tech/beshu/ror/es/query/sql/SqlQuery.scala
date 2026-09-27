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
package tech.beshu.ror.es.query.sql

import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.es.query.Query
import tech.beshu.ror.es.query.QueryIndicesReader.{ReadError, ReadingFailure}
import tech.beshu.ror.utils.RequestIdAwareLogging

type SqlQuery = Query[LocatedIndexList, SqlQuery.Rejection]

object SqlQuery extends RequestIdAwareLogging {

  export tech.beshu.ror.es.query.Query.{Unreadable, WithIndices, WithoutIndices}

  def from(query: String, reader: SqlQueryIndicesReader)(
      implicit requestId: RequestId
  ): SqlQuery = {
    // A cursor request has no query. ES reads the next page from the cursor, which holds a query that ROR narrowed.
    if (query.isBlank) WithoutIndices(query)
    else
      reader.indicesIn(query) match {
        case Right(indexLists) =>
          Query.readable(query, indexLists, reader)
        case Left(ReadError.QueryNotParsed(cause)) =>
          logger.debug("Elasticsearch cannot parse the SQL query", cause)
          Unreadable(query, Rejection.CannotParseQuery)
        case Left(ReadError.PlanNotRead(cause)) =>
          logger.warn("ReadonlyREST cannot read the plan Elasticsearch built for the SQL query", cause)
          Unreadable(query, Rejection.CannotReadQuery)
        case Left(ReadError.IndicesNotLocated(failure)) =>
          Unreadable(query, Rejection.CannotExtractIndices(failure))
      }
  }

  sealed trait Rejection

  object Rejection {

    case object CannotParseQuery extends Rejection

    case object CannotReadQuery extends Rejection

    final case class CannotExtractIndices(failure: ReadingFailure) extends Rejection

    final case class CannotParseRewrittenQuery(intendedIndices: List[String]) extends Rejection

    final case class SubstitutionNotConfirmed(intendedIndices: List[String], readIndices: List[String])
        extends Rejection

    final case class RewriteNotConfirmed(intendedIndices: List[String], failure: ReadingFailure) extends Rejection

  }

}
