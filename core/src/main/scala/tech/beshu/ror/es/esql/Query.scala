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
import tech.beshu.ror.utils.slf4j.Logging

sealed trait Query {

  protected def text: String

  def stringify: String = text

  def indices: Set[RequestedIndex[ClusterIndexName]]

  def narrowedTo(
      allowedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]],
      reader: EsqlIndexListsReader
  ): Either[Rejection, Query]

}

object Query extends Logging {

  private[esql] final case class TextSpan(start: Int, end: Int)

  /** A 1-based line and a 0-based column, the way ES reports them. */
  final case class SourceLocation(line: Int, column: Int)

  def from(query: String, reader: EsqlIndexListsReader): Query = {
    reader.indexListsIn(query) match {
      case Right(reported) =>
        located(query, reported)
      case Left(cause) =>
        logger.debug("Cannot parse the ES|QL statement", cause)
        Unreadable(query, Rejection.CannotParseQuery)
    }
  }

  final class WithIndices private[esql] (
      protected val text: String,
      private[esql] val indexLists: NonEmptyList[LocatedIndexList]
  ) extends Query {

    override lazy val indices: Set[RequestedIndex[ClusterIndexName]] =
      LocatedIndexList.requestedIndicesOf(indexLists)

    override def narrowedTo(
        allowedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]],
        reader: EsqlIndexListsReader
    ): Either[Rejection, Query] = {
      if (allowedIndices.toList.toCovariantSet == indices) Right(this)
      else {
        val replaced = IndexListReplacer.replacing(text, indexLists, allowedIndices)
        reader.indexListsIn(replaced.query) match {
          case Right(reported) =>
            replaced
              .checkedAgainst(reported.map(_.read).filterNot(_.indexListIsEmpty))
              .flatMap(narrowed => confirmed(located(narrowed, reported)))
          case Left(cause) =>
            logger.warn("Elasticsearch cannot parse the ES|QL query ReadonlyREST rewrote", cause)
            Left(Rejection.CannotParseRewrittenQuery(replaced.intendedIndexLists))
        }
      }
    }

    private def confirmed(narrowed: Query): Either[Rejection, Query] = narrowed match {
      case Unreadable(_, reason) => Left(reason)
      case query                 => Right(query)
    }

  }

  final case class WithoutIndices(text: String) extends Query {

    override def indices: Set[RequestedIndex[ClusterIndexName]] = allIndices

    override def narrowedTo(
        allowedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]],
        reader: EsqlIndexListsReader
    ): Either[Rejection, Query] = Right(this)

  }

  final case class Unreadable(text: String, reason: Rejection) extends Query {

    override def indices: Set[RequestedIndex[ClusterIndexName]] = allIndices

    override def narrowedTo(
        allowedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]],
        reader: EsqlIndexListsReader
    ): Either[Rejection, Query] = {
      Either.cond[Rejection, Query](allowedIndices.toList.toCovariantSet == indices, this, reason)
    }

  }

  private def allIndices: Set[RequestedIndex[ClusterIndexName]] =
    Set(RequestedIndex(ClusterIndexName.Local.wildcard, excluded = false))

  private def located(query: String, reported: List[ReportedIndexList]): Query = {
    IndexListLocator.locatedIn(query, reported) match {
      case Left(failure) =>
        Unreadable(query, Rejection.CannotExtractIndices(failure))
      case Right(indexLists) =>
        NonEmptyList.fromList(indexLists) match {
          case Some(nonEmptyIndexLists) => new WithIndices(query, nonEmptyIndexLists)
          case None                     => WithoutIndices(query)
        }
    }
  }

}
