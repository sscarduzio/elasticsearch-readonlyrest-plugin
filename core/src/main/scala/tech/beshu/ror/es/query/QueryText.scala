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

import scala.annotation.tailrec

private[es] object QueryText {

  /**
   * The spans at the place ES reports for the pattern that hold its text. There is one candidate span for each
   * column unit, and a unit that gives no offset or a span with other text gives no span.
   */
  def spansOf(query: String, pattern: IndexPatternInQuery, columnUnits: List[ColumnUnit]): List[TextSpan] =
    lineStartOf(query, pattern.writtenAt).toList
      .flatMap(lineStart => columnUnits.flatMap(_.offsetIn(query, lineStart, pattern.writtenAt.column)))
      .distinct
      .map(start => TextSpan(start, start + pattern.writtenText.length))
      .filter(span => span.end <= query.length && query.substring(span.start, span.end) == pattern.writtenText)

  def firstOverlapIn(query: String, spans: List[TextSpan]): Option[(String, String)] =
    spans
      .sortBy(span => (span.start, span.end))
      .sliding(2)
      .collectFirst {
        case List(one, next) if next.start < one.end || next.start == one.start =>
          (query.substring(one.start, one.end), query.substring(next.start, next.end))
      }

  def rewritten(query: String, edits: List[(TextSpan, String)]): String =
    edits.sortBy { case (span, _) => -span.start }.foldLeft(query) { case (text, (span, replacement)) =>
      s"${text.substring(0, span.start)}$replacement${text.substring(span.end)}"
    }

  /**
   * Compared with the quoting and the spacing dropped from both sides: ES hides whitespace inside a list
   * (`FROM remote : idx`) and reports the list unquoted, neither of which makes it a different list.
   */
  def sameIndexList(one: String, other: String): Boolean =
    quotingAndSpacingAside(one) == quotingAndSpacingAside(other)

  private def quotingAndSpacingAside(indexList: String): String =
    indexList.filterNot(char => char.isWhitespace || char == '"')

  private def lineStartOf(query: String, location: SourceLocation): Option[Int] = {
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
  }

}
