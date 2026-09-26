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
package tech.beshu.ror.es.query.esql

import cats.data.NonEmptyList
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestId, RequestedIndex}
import tech.beshu.ror.es.query.Query.narrowedWithoutRewrite
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.RequestIdAwareLogging

type EsqlQuery = tech.beshu.ror.es.query.Query[EsqlQuery.Rejection]

object EsqlQuery extends RequestIdAwareLogging {

  export tech.beshu.ror.es.query.Query.{Unreadable, WithoutIndices}

  def from(query: String, reader: EsqlQueryIndicesReader)(
      implicit requestId: RequestId
  ): EsqlQuery = {
    reader.indicesIn(query) match {
      case Right(indexLists) =>
        readable(query, indexLists)
      case Left(ReadError.QueryNotParsed(cause)) =>
        logger.debug("Cannot parse the ES|QL statement", cause)
        Unreadable(query, Rejection.CannotParseQuery)
      case Left(ReadError.IndicesNotLocated(failure)) =>
        Unreadable(query, Rejection.CannotExtractIndices(failure))
    }
  }

  def narrowed(
      query: EsqlQuery,
      filteredRequestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]],
      reader: EsqlQueryIndicesReader
  )(
      implicit requestId: RequestId
  ): Either[Rejection, EsqlQuery] =
    query match {
      case withIndices: WithIndices => narrowedWithRewrite(withIndices, filteredRequestedIndices, reader)
      case other: (WithoutIndices | Unreadable[Rejection]) => narrowedWithoutRewrite(other, filteredRequestedIndices)
    }

  final case class WithIndices private[esql] (
      text: String,
      private[esql] val indexLists: NonEmptyList[LocatedIndexList]
  ) extends EsqlQuery {

    override lazy val indices: Set[RequestedIndex[ClusterIndexName]] =
      indexLists.toList.flatMap(_.requestedIndices.toList).toCovariantSet

  }

  private def narrowedWithRewrite(
      query: WithIndices,
      filteredRequestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]],
      reader: EsqlQueryIndicesReader
  )(
      implicit requestId: RequestId
  ): Either[Rejection, EsqlQuery] = {
    if (filteredRequestedIndices.toList.toCovariantSet == query.indices) Right(query)
    else {
      val replaced = IndexListReplacer.replacing(query.text, query.indexLists, filteredRequestedIndices)
      reader.indicesIn(replaced.query) match {
        case Right(readIndexLists) =>
          replaced.checkedAgainst(readIndexLists) match {
            case Right(narrowed) =>
              Right(readable(narrowed, readIndexLists))
            case Left(rejection) =>
              logger.debug(s"The ES|QL query [${query.text}] was rewritten to [${replaced.query}]")
              Left(rejection)
          }
        case Left(ReadError.QueryNotParsed(cause)) =>
          logger.warn("Elasticsearch cannot parse the ES|QL query ReadonlyREST rewrote", cause)
          Left(Rejection.CannotParseRewrittenQuery(replaced.intendedIndexLists))
        case Left(ReadError.IndicesNotLocated(failure)) =>
          Left(Rejection.RewriteNotConfirmed(replaced.intendedIndexLists, failure))
      }
    }
  }

  private def readable(query: String, indexLists: List[LocatedIndexList]): EsqlQuery =
    NonEmptyList.fromList(indexLists) match {
      case Some(nonEmptyIndexLists) => WithIndices(query, nonEmptyIndexLists)
      case None                     => WithoutIndices(query)
    }

  sealed trait Rejection

  object Rejection {

    case object CannotParseQuery extends Rejection

    final case class CannotExtractIndices(failure: ReadingFailure) extends Rejection

    final case class CannotParseRewrittenQuery(intendedIndexLists: List[String]) extends Rejection

    final case class SubstitutionNotConfirmed(intendedIndexLists: List[String], readIndexLists: List[String])
        extends Rejection

    final case class RewriteNotConfirmed(intendedIndexLists: List[String], failure: ReadingFailure) extends Rejection

  }

}

private[esql] sealed trait ReadError

private[esql] object ReadError {

  final case class QueryNotParsed(cause: Throwable) extends ReadError

  final case class IndicesNotLocated(failure: ReadingFailure) extends ReadError

}

sealed trait ReadingFailure

object ReadingFailure {

  final case class NotWhereEsReportedIt(reportedIndexList: String) extends ReadingFailure

  final case class SubqueryInSourceCommand(reportedIndexList: String) extends ReadingFailure

  case object IndexListInAnonymousParameter extends ReadingFailure

  final case class UnsupportedIndexList(reportedIndexList: String) extends ReadingFailure

  final case class OverlappingIndexLists(oneWrittenAs: String, otherWrittenAs: String) extends ReadingFailure

}
