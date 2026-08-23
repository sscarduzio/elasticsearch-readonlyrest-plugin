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

import cats.implicits.*
import tech.beshu.ror.es.esql.LocatedIndexList.ReadingFailure
import tech.beshu.ror.es.esql.Query.{SourceLocation, TextSpan}

import scala.annotation.tailrec
import scala.util.matching.Regex

/**
 * ES reports an index list normalized (`FROM a, b` as `a,b`), so it cannot be searched for in the query - only the
 * source location ES keeps next to it turns it into a span to rewrite. A `LOOKUP JOIN` target is located exactly;
 * a source command is located whole (`FROM a, b METADATA _index`), so its list is picked out of the command text
 * literally - and either way, only once the span reads back as the list ES reported.
 */
object IndexListLocator {

  /**
   * Everything between the command keyword and a `METADATA` clause, in either the current or the ES 8.x form. The
   * list has to end on a character that could close an index name, or `FROM a, metadata` would read its last index
   * as the start of the clause.
   */
  private val sourceCommandIndexList: Regex =
    """(?is)^\s*(?:FROM|TS)\s+(.*?[^\s,])(?:\s+METADATA\b.*|\s*\[\s*METADATA\b.*|\s*)$""".r

  private val promqlCommand: Regex = """(?is)^\s*PROMQL\b.*""".r

  private val comment: Regex = """(?s)/\*.*?(?:\*/|$)|//[^\n]*""".r

  private val queryParameter: Regex = """^\s*\?\??[A-Za-z_0-9]*\s*$""".r

  def locatedIn(
      query: Query,
      reported: List[ReportedIndexList]
  ): Either[ReadingFailure, List[LocatedIndexList]] =
    reported.filterNot(_.read.namesNoIndex).traverse(locate(query.value, _))

  private def locate(
      query: String,
      reported: ReportedIndexList
  ): Either[ReadingFailure, LocatedIndexList] = {
    val commandText = withoutComments(reported.writtenText)
    for {
      writtenSpan <- writtenSpanOf(query, reported)
      indexList <- indexListSpanIn(reported, commandText, writtenSpan)
      _ <- checkHoldsReportedIndexList(reported.read, indexList.text)
      locatedIndexList <- indexListAt(indexList.span, reported.read)
    } yield locatedIndexList
  }

  private def writtenSpanOf(
      query: String,
      reported: ReportedIndexList
  ): Either[ReadingFailure, TextSpan] = {
    offsetOf(query, reported.writtenAt)
      .map(start => TextSpan(start, start + reported.writtenText.length))
      .filter(span => span.end <= query.length && query.substring(span.start, span.end) == reported.writtenText)
      .toRight(ReadingFailure.NotWhereEsReportedIt(reported.read.indexList))
  }

  private def offsetOf(query: String, location: SourceLocation): Option[Int] = {
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
      .map(_ + location.column)
      .filter(_ <= query.length)
  }

  private def indexListSpanIn(
      reported: ReportedIndexList,
      commandText: String,
      writtenSpan: TextSpan
  ): Either[ReadingFailure, IndexListSpan] = {
    reported.read match {
      case _: IndexListRead.ByLookupJoin =>
        Right(IndexListSpan(writtenSpan, commandText))
      case _: IndexListRead.BySourceCommand =>
        sourceCommandIndexList.findFirstMatchIn(commandText) match {
          case Some(indexList) =>
            val span = TextSpan(writtenSpan.start + indexList.start(1), writtenSpan.start + indexList.end(1))
            Either.cond(
              // a subquery entry is merged into the reported list, leaving it no span of its own
              test = !indexList.group(1).contains('('),
              right = IndexListSpan(span, indexList.group(1)),
              left = ReadingFailure.SubqueryInSourceCommand(reported.read.indexList)
            )
          case None if promqlCommand.matches(commandText) =>
            Left(ReadingFailure.PromqlLeaningOnDefaultIndex)
          case None =>
            // a `PROMQL` command's `index=` parameter: ES locates its value exactly
            Right(IndexListSpan(writtenSpan, commandText))
        }
    }
  }

  /**
   * ES reports the list it read, not the text it read it from, so a span is only safe to rewrite once it reads back
   * as that list - a parameter excepted, since its value is not written in the query at all.
   */
  private def checkHoldsReportedIndexList(read: IndexListRead, spanText: String): Either[ReadingFailure, Unit] =
    Either.cond(
      test = queryParameter.matches(spanText) || sameIndexList(spanText, read.indexList),
      right = (),
      left = ReadingFailure.NotWhereEsReportedIt(read.indexList)
    )

  /**
   * Compared with the quoting and the spacing dropped from both sides: ES hides whitespace anywhere inside a source
   * command (`FROM remote : idx`) and reports the list unquoted, neither of which makes it a different list.
   */
  private def sameIndexList(one: String, other: String): Boolean =
    quotingAndSpacingAside(one) == quotingAndSpacingAside(other)

  private def quotingAndSpacingAside(indexList: String): String =
    indexList.filterNot(char => char.isWhitespace || char == '"')

  /** Blanked, not dropped, so what is left keeps its offsets. */
  private def withoutComments(commandText: String): String =
    comment.replaceAllIn(commandText, found => " " * found.matched.length)

  private def indexListAt(span: TextSpan, read: IndexListRead): Either[ReadingFailure, LocatedIndexList] = {
    val indexList = read match {
      case read: IndexListRead.ByLookupJoin    => LocatedIndexList.LookupJoinTarget.parse(span, read)
      case read: IndexListRead.BySourceCommand => LocatedIndexList.SourceCommandIndices.parse(span, read)
    }
    indexList.toRight(ReadingFailure.UnsupportedIndexList(read.indexList))
  }

  private final case class IndexListSpan(span: TextSpan, text: String)

}
