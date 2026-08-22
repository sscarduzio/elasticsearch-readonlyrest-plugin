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

import scala.annotation.tailrec
import scala.util.matching.Regex

/**
 * ES reports an index list normalized (`FROM a, b` as `a,b`), so searching the query for it silently finds nothing
 * and leaves the user's own indices in place. What makes an index list replaceable is the source location ES's
 * parser keeps next to every one of them - this turns it into the span of query text to rewrite.
 *
 * A `LOOKUP JOIN` target is located exactly. A source command is located as a whole (`FROM a, b METADATA _index`),
 * so its index list still has to be picked out of the command's own text - which is all this does, and it does it
 * literally, because [[EsqlIndexListReplacing.verified]] holds the result to what ES reads back out of the query.
 *
 * A source command whose every entry is a subquery names no index of its own - ES reports it an empty index list,
 * and the subqueries it holds are reported as source commands in their own right.
 */
object EsqlIndexListLocator {

  /** Everything between the command keyword and a `METADATA` clause, in either the current or the ES 8.x form. */
  private val sourceCommandIndexList: Regex =
    """(?is)^\s*(?:FROM|TS)\s+(.+?)(?:\s+METADATA\b.*|\s*\[\s*METADATA\b.*|\s*)$""".r

  private val promqlCommand: Regex = """(?is)^\s*PROMQL\b.*""".r

  private val comment: Regex = """(?s)/\*.*?(?:\*/|$)|//[^\n]*""".r

  def indexTablesIn(
      query: EsqlQuery,
      relations: List[EsqlReportedRelation]
  ): Either[EsqlIndexListReadingFailure, List[EsqlIndexTable]] =
    relations.filterNot(_.indexList.isBlank).traverse(indexTableIn(query.value, _))

  private def indexTableIn(
      query: String,
      relation: EsqlReportedRelation
  ): Either[EsqlIndexListReadingFailure, EsqlIndexTable] = {
    for {
      writtenSpan <- writtenSpanOf(query, relation)
      indexListSpan <- indexListSpanIn(relation, writtenSpan)
      table <- tableFrom(relation, indexListSpan)
    } yield table
  }

  private def writtenSpanOf(
      query: String,
      relation: EsqlReportedRelation
  ): Either[EsqlIndexListReadingFailure, QueryTextSpan] = {
    offsetOf(query, relation.writtenAt)
      .map(start => QueryTextSpan(start, start + relation.writtenText.length))
      .filter(span => span.end <= query.length && query.substring(span.start, span.end) == relation.writtenText)
      .toRight(EsqlIndexListReadingFailure.NotWhereEsReportedIt(relation.indexList))
  }

  private def offsetOf(query: String, location: EsqlSourceLocation): Option[Int] = {
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
      relation: EsqlReportedRelation,
      writtenSpan: QueryTextSpan
  ): Either[EsqlIndexListReadingFailure, QueryTextSpan] = {
    if (relation.isLookupJoin) {
      Right(writtenSpan)
    } else {
      sourceCommandIndexList.findFirstMatchIn(withoutComments(relation.writtenText)) match {
        case Some(indexList) =>
          val span = QueryTextSpan(writtenSpan.start + indexList.start(1), writtenSpan.start + indexList.end(1))
          Either.cond(
            // a subquery entry is reported merged into the surrounding list, so its span cannot be rewritten alone
            test = !relation.writtenText.substring(indexList.start(1), indexList.end(1)).contains('('),
            right = span,
            left = EsqlIndexListReadingFailure.SubqueryInSourceCommand(relation.indexList)
          )
        case None if promqlCommand.matches(relation.writtenText) =>
          Left(EsqlIndexListReadingFailure.PromqlLeaningOnDefaultIndex)
        case None =>
          // a `PROMQL` command's `index=` parameter: ES locates its value, so there is nothing left to pick out
          Right(writtenSpan)
      }
    }
  }

  /** Blanked rather than dropped, so what is left sits where it was written and its span still means something. */
  private def withoutComments(commandText: String): String =
    comment.replaceAllIn(commandText, found => " " * found.matched.length)

  private def tableFrom(
      relation: EsqlReportedRelation,
      indexListSpan: QueryTextSpan
  ): Either[EsqlIndexListReadingFailure, EsqlIndexTable] = {
    val table =
      if (relation.isLookupJoin) EsqlIndexTable.LookupJoin.parse(indexListSpan, relation.indexList)
      else EsqlIndexTable.SourceCommand.parse(indexListSpan, relation.indexList)
    table.toRight(EsqlIndexListReadingFailure.UnsupportedIndexList(relation.indexList))
  }

}
