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
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestId, RequestedIndex}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.RequestIdAwareLogging

sealed trait Query {

  protected def text: String

  def stringify: String = text

  def indices: Set[RequestedIndex[ClusterIndexName]]

  def narrowedTo(
      allowedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
  )(
      implicit requestId: RequestId
  ): Either[Rejection, Query]

}

object Query extends RequestIdAwareLogging {

  def from(query: String, reader: EsqlQueryIndicesReader)(
      implicit requestId: RequestId
  ): Query = {
    reader.indicesIn(query) match {
      case Right(indexLists) =>
        readable(query, reader, indexLists)
      case Left(ReadError.QueryNotParsed(cause)) =>
        logger.debug("Cannot parse the ES|QL statement", cause)
        Unreadable(query, Rejection.CannotParseQuery)
      case Left(ReadError.IndicesNotLocated(failure)) =>
        Unreadable(query, Rejection.CannotExtractIndices(failure))
    }
  }

  final class WithIndices private[esql] (
      protected val text: String,
      private val reader: EsqlQueryIndicesReader,
      private[esql] val indexLists: NonEmptyList[LocatedIndexList]
  ) extends Query {

    override lazy val indices: Set[RequestedIndex[ClusterIndexName]] =
      indexLists.toList.flatMap(_.requestedIndices.toList).toCovariantSet

    override def narrowedTo(
        allowedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
    )(
        implicit requestId: RequestId
    ): Either[Rejection, Query] = {
      if (allowedIndices.toList.toCovariantSet == indices) Right(this)
      else {
        val replaced = IndexListReplacer.replacing(text, indexLists, allowedIndices)
        reader.indicesIn(replaced.query) match {
          case Right(readIndexLists) =>
            replaced
              .checkedAgainst(readIndexLists)
              .map(narrowed => readable(narrowed, reader, readIndexLists))
          case Left(ReadError.QueryNotParsed(cause)) =>
            logger.warn("Elasticsearch cannot parse the ES|QL query ReadonlyREST rewrote", cause)
            Left(Rejection.CannotParseRewrittenQuery(replaced.intendedIndexLists))
          case Left(ReadError.IndicesNotLocated(failure)) =>
            Left(Rejection.RewriteNotConfirmed(replaced.intendedIndexLists, failure))
        }
      }
    }

  }

  final case class WithoutIndices(text: String) extends Query {

    override def indices: Set[RequestedIndex[ClusterIndexName]] = allIndices

    override def narrowedTo(
        allowedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
    )(
        implicit requestId: RequestId
    ): Either[Rejection, Query] = Right(this)

  }

  final case class Unreadable(text: String, reason: Rejection) extends Query {

    override def indices: Set[RequestedIndex[ClusterIndexName]] = allIndices

    override def narrowedTo(
        allowedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
    )(
        implicit requestId: RequestId
    ): Either[Rejection, Query] = {
      Either.cond[Rejection, Query](allowedIndices.toList.toCovariantSet == indices, this, reason)
    }

  }

  private def allIndices: Set[RequestedIndex[ClusterIndexName]] =
    Set(RequestedIndex(ClusterIndexName.Local.wildcard, excluded = false))

  private def readable(query: String, reader: EsqlQueryIndicesReader, indexLists: List[LocatedIndexList]): Query =
    NonEmptyList.fromList(indexLists) match {
      case Some(nonEmptyIndexLists) => new WithIndices(query, reader, nonEmptyIndexLists)
      case None                     => WithoutIndices(query)
    }

}

private[esql] sealed trait ReadError

private[esql] object ReadError {

  final case class QueryNotParsed(cause: Throwable) extends ReadError

  final case class IndicesNotLocated(failure: ReadingFailure) extends ReadError

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

sealed trait ReadingFailure

object ReadingFailure {

  final case class NotWhereEsReportedIt(reportedIndexList: String) extends ReadingFailure

  final case class SubqueryInSourceCommand(reportedIndexList: String) extends ReadingFailure

  case object IndexListInAnonymousParameter extends ReadingFailure

  final case class UnsupportedIndexList(reportedIndexList: String) extends ReadingFailure

  final case class OverlappingIndexLists(oneWrittenAs: String, otherWrittenAs: String) extends ReadingFailure

}
