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
package tech.beshu.ror.es.sql

import cats.data.NonEmptyList
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestId, RequestedIndex}
import tech.beshu.ror.es.query.Query.narrowedWithoutRewrite
import tech.beshu.ror.es.sql.SqlQueryIndicesReader.ReadError
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.RequestIdAwareLogging

type SqlQuery = tech.beshu.ror.es.query.Query[SqlQuery.Rejection]

object SqlQuery extends RequestIdAwareLogging {

  export tech.beshu.ror.es.query.Query.{Unreadable, WithoutIndices}

  def from(query: String, reader: SqlQueryIndicesReader)(
      implicit requestId: RequestId
  ): SqlQuery = {
    // A cursor request has no query. ES reads the next page from the cursor, which holds a query that ROR narrowed.
    if (query.isBlank) WithoutIndices(query)
    else
      reader.indicesIn(query) match {
        case Right(indexLists) =>
          readable(query, indexLists)
        case Left(ReadError.RejectedByEs(cause)) =>
          logger.debug("Elasticsearch cannot parse the SQL query", cause)
          Unreadable(query, Rejection.CannotParseQuery)
        case Left(ReadError.PlanNotRead(cause)) =>
          logger.warn("ReadonlyREST cannot read the plan Elasticsearch built for the SQL query", cause)
          Unreadable(query, Rejection.CannotReadQuery)
        case Left(ReadError.IndicesNotLocated(failure)) =>
          Unreadable(query, Rejection.CannotExtractIndices(failure))
      }
  }

  def narrowed(
      query: SqlQuery,
      filteredRequestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]],
      reader: SqlQueryIndicesReader
  )(
      implicit requestId: RequestId
  ): Either[Rejection, SqlQuery] =
    query match {
      case withIndices: WithIndices => narrowedWithRewrite(withIndices, filteredRequestedIndices, reader)
      case other: (WithoutIndices | Unreadable[Rejection]) => narrowedWithoutRewrite(other, filteredRequestedIndices)
    }

  final case class WithIndices private[sql] (
      text: String,
      private[sql] val indexLists: NonEmptyList[LocatedIndexList]
  ) extends SqlQuery {

    override lazy val indices: Set[RequestedIndex[ClusterIndexName]] =
      indexLists.toList.flatMap(_.requestedIndices.toList).toCovariantSet

  }

  private def narrowedWithRewrite(
      query: WithIndices,
      filteredRequestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]],
      reader: SqlQueryIndicesReader
  )(
      implicit requestId: RequestId
  ): Either[Rejection, SqlQuery] = {
    if (filteredRequestedIndices.toList.toCovariantSet == query.indices) Right(query)
    else {
      val replaced = IndexListReplacer.replacing(query.text, query.indexLists, filteredRequestedIndices)
      val intendedIndices = replaced.intendedIndices.toList.sorted
      reader.indicesIn(replaced.query) match {
        case Right(readIndexLists) =>
          replaced.checkedAgainst(readIndexLists) match {
            case Right(narrowed) =>
              Right(readable(narrowed, readIndexLists))
            case Left(rejection) =>
              logger.debug(s"The SQL query [${query.text}] was rewritten to [${replaced.query}]")
              Left(rejection)
          }
        case Left(ReadError.RejectedByEs(cause)) =>
          logger.warn("Elasticsearch cannot parse the SQL query ReadonlyREST rewrote", cause)
          Left(Rejection.CannotParseRewrittenQuery(intendedIndices))
        case Left(ReadError.PlanNotRead(cause)) =>
          logger.warn("ReadonlyREST cannot read the plan Elasticsearch built for the rewritten SQL query", cause)
          Left(Rejection.CannotReadQuery)
        case Left(ReadError.IndicesNotLocated(failure)) =>
          Left(Rejection.RewriteNotConfirmed(intendedIndices, failure))
      }
    }
  }

  private def readable(query: String, indexLists: List[LocatedIndexList]): SqlQuery =
    NonEmptyList.fromList(indexLists) match {
      case Some(nonEmptyIndexLists) => WithIndices(query, nonEmptyIndexLists)
      case None                     => WithoutIndices(query)
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

sealed trait ReadingFailure

object ReadingFailure {

  final case class NotWhereEsReportedIt(indexList: String) extends ReadingFailure

  final case class UnsupportedIndexList(indexList: String) extends ReadingFailure

  final case class IndexListNotWrittenOnce(indexList: String) extends ReadingFailure

  final case class PatternNotWrittenOnce(commandName: String, indexNameWildcard: String) extends ReadingFailure

  final case class CommandTakesNoIndexList(commandName: String) extends ReadingFailure

  final case class OverlappingIndexLists(oneWrittenAs: String, otherWrittenAs: String) extends ReadingFailure

}
