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
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.accesscontrol.domain.RequestedIndex
import tech.beshu.ror.es.sql.CommandSelector.{
  AppendableIndexList,
  CannotNarrow,
  LiteralIndexList,
  MatchingPattern,
  NotIndexRelated
}

import scala.annotation.tailrec
import scala.util.Try
import scala.util.matching.Regex

private[sql] object IndexListLocator {

  private val likeClause: Regex =
    """(?is)\bLIKE\s+'(?:[^']|'')*'(?:\s+ESCAPE\s+'(?:[^']|'')*')?""".r

  def locatedTable(query: String, table: TableInQuery): Either[ReadingFailure, LocatedIndexList] =
    for {
      span <- spanOf(query, table)
      indices <- requestedIndicesIn(table.reportedIndexList)
    } yield LocatedIndexList(span, indices, IndexListSyntax.InQueryText)

  def locatedSelector(query: String, selector: CommandSelector): Either[ReadingFailure, List[LocatedIndexList]] =
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
    LocatedIndexList
      .requestedIndicesIn(indexList)
      .toRight(ReadingFailure.UnsupportedIndexList(indexList))

  private def spanOf(query: String, table: TableInQuery): Either[ReadingFailure, TextSpan] =
    offsetsOf(query, table.writtenAt)
      .map(start => TextSpan(start, start + table.writtenText.length))
      .filter(span => span.end <= query.length && query.substring(span.start, span.end) == table.writtenText) match {
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

  private def offsetsOf(query: String, location: SourceLocation): List[Int] = {
    @tailrec
    def startOfLine(idx: Int, line: Int): Option[Int] = {
      if (line >= location.line) Some(idx)
      else
        query.indexOf('\n', idx) match {
          case -1        => None
          case newLineAt => startOfLine(newLineAt + 1, line + 1)
        }
    }
    Option
      .when(location.line >= 1 && location.column >= 0)(())
      .flatMap(_ => startOfLine(0, 1))
      .toList
      .flatMap { lineStart =>
        // ES before 7.15 parses through an ANTLRInputStream, which counts the column in UTF-16 units;
        // ES 7.15+ parses through a CodePointCharStream, which counts it in code points
        val inUtf16Units = lineStart + location.column
        val inCodePoints = Try(query.offsetByCodePoints(lineStart, location.column)).toOption
        (inUtf16Units :: inCodePoints.toList).distinct
      }
  }

  private def sameIndexList(one: String, other: String): Boolean =
    quotingAndSpacingAside(one) == quotingAndSpacingAside(other)

  private def quotingAndSpacingAside(indexList: String): String =
    indexList.filterNot(char => char.isWhitespace || char == '"')

}
