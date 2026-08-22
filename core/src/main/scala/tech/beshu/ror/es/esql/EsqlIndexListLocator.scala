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

/**
 * ES reports an index list normalized (`FROM a, b` as `a,b`), so searching the query for it silently finds nothing
 * and leaves the user's own indices in place. What makes an index list replaceable is the source location ES's
 * parser keeps next to every one of them - this turns it into the span of query text to rewrite.
 *
 * A `LOOKUP JOIN` target is located exactly. A source command is located as a whole (`FROM a, b METADATA _index`),
 * so its index list still has to be picked out of the command's own text. Picking it out is deliberately literal -
 * anything an index list cannot hold ends it - because the result is checked against what ES reads back out of the
 * rewritten query, which is what a mistake here has to answer to.
 *
 * A source command whose every entry is a subquery names no index of its own - ES reports it an empty index list,
 * and the subqueries it holds are reported as source commands in their own right.
 */
object EsqlIndexListLocator {

  private val sourceCommandKeywords = Set("FROM", "TS")
  private val promqlKeyword = "PROMQL"
  private val metadataKeyword = "METADATA"

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
      indexListSpan <- indexListSpanIn(query, relation, writtenSpan)
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
      query: String,
      relation: EsqlReportedRelation,
      writtenSpan: QueryTextSpan
  ): Either[EsqlIndexListReadingFailure, QueryTextSpan] = {
    if (relation.isLookupJoin) {
      Right(writtenSpan)
    } else {
      val keywordAt = firstNonWhitespaceFrom(query, writtenSpan.start, writtenSpan.end)
      val keyword = wordAt(query, keywordAt)
      if (sourceCommandKeywords.exists(_.equalsIgnoreCase(keyword))) {
        indexListAfterKeyword(query, keywordAt + keyword.length, writtenSpan, relation)
      } else if (keyword.equalsIgnoreCase(promqlKeyword)) {
        Left(EsqlIndexListReadingFailure.PromqlLeaningOnDefaultIndex)
      } else {
        // a `PROMQL` command's `index=` parameter: ES locates its value, so there is nothing left to pick out
        Right(writtenSpan)
      }
    }
  }

  private def indexListAfterKeyword(
      query: String,
      from: Int,
      commandSpan: QueryTextSpan,
      relation: EsqlReportedRelation
  ): Either[EsqlIndexListReadingFailure, QueryTextSpan] = {
    val start = firstNonWhitespaceFrom(query, from, commandSpan.end)
    endOfIndexList(query, start, start, commandSpan.end)
      .map(stoppedAt => QueryTextSpan(start, Math.max(start, lastNonSeparatorIn(query, start, stoppedAt))))
      .toRight(EsqlIndexListReadingFailure.SubqueryInSourceCommand(relation.indexList))
  }

  /**
   * A `(` opens a subquery entry, which ES reports merged into the surrounding list - rewriting the merged span
   * would swallow the subquery, so such a command is left unread. Everything else that an index list cannot hold
   * simply ends it, which is what keeps a `METADATA` clause, a bracketed one, or a comment out of the span.
   */
  @tailrec
  private def endOfIndexList(query: String, listStart: Int, idx: Int, commandEnd: Int): Option[Int] = {
    if (idx >= commandEnd) Some(commandEnd)
    else if (query.charAt(idx) == '(') None
    else if (isQuote(query.charAt(idx)))
      endOfIndexList(query, listStart, endOfQuoted(query, idx, commandEnd), commandEnd)
    else if (!isIndexListChar(query.charAt(idx))) Some(idx)
    else if (idx > listStart && startsSeparateWord(query, idx) && wordAt(query, idx).equalsIgnoreCase(metadataKeyword))
      Some(idx)
    else endOfIndexList(query, listStart, idx + 1, commandEnd)
  }

  private def tableFrom(
      relation: EsqlReportedRelation,
      indexListSpan: QueryTextSpan
  ): Either[EsqlIndexListReadingFailure, EsqlIndexTable] = {
    val table =
      if (relation.isLookupJoin) EsqlIndexTable.LookupJoin.parse(indexListSpan, relation.indexList)
      else EsqlIndexTable.SourceCommand.parse(indexListSpan, relation.indexList)
    table.toRight(EsqlIndexListReadingFailure.UnsupportedIndexList(relation.indexList))
  }

  private def isIndexListChar(char: Char): Boolean =
    char.isLetterOrDigit || char.isWhitespace || "_-*.,:+%".contains(char)

  private def isQuote(char: Char): Boolean = char == '"' || char == '`'

  /** ES lexes a quoted index name as one token, so nothing inside it can end the list. */
  private def endOfQuoted(query: String, quoteAt: Int, limit: Int): Int = {
    val closer = query.charAt(quoteAt)
    val triple = query.startsWith(closer.toString * 3, quoteAt)
    val opensAfter = if (triple) quoteAt + 3 else quoteAt + 1
    val closesAt = query.indexOf(if (triple) closer.toString * 3 else closer.toString, opensAfter)
    if (closesAt < 0 || closesAt >= limit) limit else closesAt + (if (triple) 3 else 1)
  }

  /**
   * ES lexes an index pattern as a single token, so `app-metadata` holds no `METADATA` keyword - only a blank tells
   * the keyword apart from the pattern.
   */
  private def startsSeparateWord(query: String, idx: Int): Boolean =
    idx > 0 && query.charAt(idx - 1).isWhitespace

  @tailrec
  private def firstNonWhitespaceFrom(query: String, idx: Int, limit: Int): Int =
    if (idx >= limit || !query.charAt(idx).isWhitespace) idx else firstNonWhitespaceFrom(query, idx + 1, limit)

  @tailrec
  private def lastNonSeparatorIn(query: String, from: Int, until: Int): Int = {
    if (until <= from) until
    else if (query.charAt(until - 1).isWhitespace || query.charAt(until - 1) == ',')
      lastNonSeparatorIn(query, from, until - 1)
    else until
  }

  private def wordAt(query: String, idx: Int): String = {
    @tailrec
    def endOfWord(at: Int): Int = if (at < query.length && isWordChar(query.charAt(at))) endOfWord(at + 1) else at
    if (idx < query.length) query.substring(idx, endOfWord(idx)) else ""
  }

  private def isWordChar(char: Char): Boolean = char.isLetterOrDigit || char == '_'

}
