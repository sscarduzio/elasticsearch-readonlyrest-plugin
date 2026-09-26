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

import cats.data.NonEmptyList
import cats.syntax.traverse.*
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.accesscontrol.domain.RequestedIndex
import tech.beshu.ror.es.query.QueryText.sameIndexList
import tech.beshu.ror.es.query.sql.CommandSelector.{
  AppendableIndexList,
  CannotNarrow,
  LiteralIndexList,
  MatchingPattern,
  NotIndexRelated
}
import tech.beshu.ror.es.query.sql.SqlQueryIndicesReader.QueryIndices
import tech.beshu.ror.es.query.{ColumnUnit, IndexLists, IndexPatternInQuery, QueryText, TextSpan}

import scala.annotation.tailrec
import scala.util.matching.Regex

private[sql] object IndexListLocator {

  private val likeClause: Regex =
    """(?is)\bLIKE\s+'(?:[^']|'')*'(?:\s+ESCAPE\s+'(?:[^']|'')*')?""".r

  def locatedIn(query: String, indices: QueryIndices): Either[ReadingFailure, List[LocatedIndexList]] =
    indices match {
      case QueryIndices.StatementTables(tables) =>
        for {
          indexLists <- tables.distinct.traverse(locatedTable(query, _))
          _ <- checkNoneOverlaps(query, indexLists)
        } yield indexLists
      case QueryIndices.CommandIndices(selector) =>
        locatedSelector(query, selector)
    }

  private def checkNoneOverlaps(query: String, indexLists: List[LocatedIndexList]): Either[ReadingFailure, Unit] =
    QueryText
      .firstOverlapIn(query, indexLists.map(_.span))
      .map { case (one, other) => ReadingFailure.OverlappingIndexLists(one, other) }
      .toLeft(())

  private def locatedTable(query: String, table: IndexPatternInQuery): Either[ReadingFailure, LocatedIndexList] =
    for {
      span <- spanOf(query, table)
      indices <- requestedIndicesIn(table.reportedIndexList)
    } yield LocatedIndexList(span, indices, IndexListSyntax.InQueryText)

  private def locatedSelector(
      query: String,
      selector: CommandSelector
  ): Either[ReadingFailure, List[LocatedIndexList]] =
    selector match {
      case NotIndexRelated =>
        Right(Nil)
      case CannotNarrow(commandName) =>
        Left(ReadingFailure.CommandTakesNoIndexList(commandName))
      case AppendableIndexList =>
        Right(
          List(
            LocatedIndexList(
              span = TextSpan(query.length, query.length),
              requestedIndices = NonEmptyList.one(RequestedIndex(ClusterIndexName.Local.wildcard, excluded = false)),
              writtenAs = IndexListSyntax.AppendedToQuery
            )
          )
        )
      case LiteralIndexList(indexList) =>
        for {
          span <- spanOfLiteral(query, indexList)
          indices <- requestedIndicesIn(indexList)
        } yield List(LocatedIndexList(span, indices, IndexListSyntax.InQueryText))
      case MatchingPattern(wildcard, commandName) =>
        for {
          span <- spanOfLikeClause(query, wildcard, commandName)
          indices <- requestedIndicesIn(wildcard)
        } yield List(LocatedIndexList(span, indices, IndexListSyntax.InQueryText))
    }

  private def requestedIndicesIn(
      indexList: String
  ): Either[ReadingFailure, NonEmptyList[RequestedIndex[ClusterIndexName]]] =
    IndexLists
      .requestedIndicesIn(indexList)
      .toRight(ReadingFailure.UnsupportedIndexList(indexList))

  private def spanOf(query: String, table: IndexPatternInQuery): Either[ReadingFailure, TextSpan] =
    // ES before 7.15 parses through an ANTLRInputStream, which counts the column in UTF-16 units;
    // ES 7.15+ parses through a CodePointCharStream, which counts it in code points
    QueryText.spansOf(query, table, List(ColumnUnit.CodePoints, ColumnUnit.Utf16Units)) match {
      case span :: Nil if sameIndexList(table.writtenText, table.reportedIndexList) => Right(span)
      case _ => Left(ReadingFailure.NotWhereEsReportedIt(table.reportedIndexList))
    }

  private def spanOfLiteral(query: String, indexList: String): Either[ReadingFailure, TextSpan] =
    onlyOccurrenceOf(query, s""""$indexList"""", _ => true)
      .orElse(onlyOccurrenceOf(query, indexList, standsAlone(query, _)))
      .toRight(ReadingFailure.IndexListNotWrittenOnce(indexList))

  private def standsAlone(query: String, span: TextSpan): Boolean =
    (span.start == 0 || query.charAt(span.start - 1).isWhitespace) &&
      (span.end == query.length || query.charAt(span.end).isWhitespace || query.charAt(span.end) == ';')

  private def spanOfLikeClause(
      query: String,
      wildcard: String,
      commandName: String
  ): Either[ReadingFailure, TextSpan] =
    likeClause.findAllMatchIn(query).toList match {
      case one :: Nil => Right(TextSpan(one.start, one.end))
      case _          => Left(ReadingFailure.PatternNotWrittenOnce(commandName, wildcard))
    }

  private def onlyOccurrenceOf(query: String, text: String, accepted: TextSpan => Boolean): Option[TextSpan] = {
    @tailrec
    def occurrences(from: Int, found: List[TextSpan]): List[TextSpan] =
      query.indexOf(text, from) match {
        case -1 => found
        case at => occurrences(at + text.length, TextSpan(at, at + text.length) :: found)
      }
    occurrences(0, Nil).filter(accepted) match {
      case span :: Nil => Some(span)
      case _           => None
    }
  }

}
