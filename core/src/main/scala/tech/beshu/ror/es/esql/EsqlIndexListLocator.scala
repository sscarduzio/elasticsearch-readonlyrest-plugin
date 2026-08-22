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
 * so its index list still has to be picked out of the command's own text.
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
  ): Either[EsqlIndexListReadingFailure, List[EsqlIndexTable]] = {
    val classifiedQuery = ClassifiedQuery.of(query.value)
    relations.filterNot(_.indexList.isBlank).traverse(indexTableIn(classifiedQuery, _))
  }

  private def indexTableIn(
      query: ClassifiedQuery,
      relation: EsqlReportedRelation
  ): Either[EsqlIndexListReadingFailure, EsqlIndexTable] = {
    for {
      writtenSpan <- writtenSpanOf(query, relation)
      indexListSpan <- indexListSpanIn(query, relation, writtenSpan)
      _ <- checkMatchesEsReport(query, indexListSpan, relation.indexList)
      table <- tableFrom(relation, indexListSpan)
    } yield table
  }

  private def writtenSpanOf(
      query: ClassifiedQuery,
      relation: EsqlReportedRelation
  ): Either[EsqlIndexListReadingFailure, QueryTextSpan] = {
    offsetOf(query, relation.writtenAt)
      .map(start => QueryTextSpan(start, start + relation.writtenText.length))
      .filter(span => span.end <= query.length && query.textIn(span) == relation.writtenText)
      .toRight(EsqlIndexListReadingFailure.NotWhereEsReportedIt(relation.indexList))
  }

  private def offsetOf(query: ClassifiedQuery, location: EsqlSourceLocation): Option[Int] = {
    @tailrec
    def startOfLine(idx: Int, line: Int): Option[Int] = {
      if (line >= location.line) Some(idx)
      else
        query.indexOfNewLineFrom(idx) match {
          case Some(newLineAt) => startOfLine(newLineAt + 1, line + 1)
          case None            => None
        }
    }
    Option
      .when(location.line >= 1 && location.column >= 0)(())
      .flatMap(_ => startOfLine(0, 1))
      .map(_ + location.column)
      .filter(offset => offset <= query.length)
  }

  private def indexListSpanIn(
      query: ClassifiedQuery,
      relation: EsqlReportedRelation,
      writtenSpan: QueryTextSpan
  ): Either[EsqlIndexListReadingFailure, QueryTextSpan] = {
    if (relation.isLookupJoin) {
      Right(writtenSpan)
    } else {
      val keywordAt = query.firstNonBlankFrom(writtenSpan.start)
      val keyword = query.wordAt(keywordAt)
      if (sourceCommandKeywords.exists(_.equalsIgnoreCase(keyword))) {
        indexListAfterKeyword(query, from = keywordAt + keyword.length, commandSpan = writtenSpan, relation)
      } else if (keyword.equalsIgnoreCase(promqlKeyword)) {
        Left(EsqlIndexListReadingFailure.PromqlLeaningOnDefaultIndex)
      } else {
        // a `PROMQL` command's `index=` parameter: ES locates its value, so there is nothing left to pick out
        Right(writtenSpan)
      }
    }
  }

  private def indexListAfterKeyword(
      query: ClassifiedQuery,
      from: Int,
      commandSpan: QueryTextSpan,
      relation: EsqlReportedRelation
  ): Either[EsqlIndexListReadingFailure, QueryTextSpan] = {
    val start = query.firstNonBlankFrom(from)
    endOfIndexList(query, listStart = start, idx = start, commandEnd = commandSpan.end)
      .map(stoppedAt => QueryTextSpan(start, Math.max(start, query.lastNonSeparatorIn(start, stoppedAt))))
      .toRight(EsqlIndexListReadingFailure.SubqueryInSourceCommand(relation.indexList))
  }

  /**
   * `[` ends a list: it opens the bracketed `METADATA` clause, the only form ES 8.11 and 8.12 accept. A `(` opens a
   * subquery entry, which ES reports merged into the surrounding list - rewriting the merged span would swallow the
   * subquery, so such a command is left unread and the query is rejected.
   */
  @tailrec
  private def endOfIndexList(query: ClassifiedQuery, listStart: Int, idx: Int, commandEnd: Int): Option[Int] = {
    if (idx >= commandEnd) Some(commandEnd)
    else if (!query.isCode(idx)) endOfIndexList(query, listStart, idx + 1, commandEnd)
    else if (query.charAt(idx) == '(') None
    else if (query.charAt(idx) == '[') Some(idx)
    else if (idx > listStart && query.isSeparateWordStart(idx) && query.wordAt(idx).equalsIgnoreCase(metadataKeyword))
      Some(idx)
    else endOfIndexList(query, listStart, idx + 1, commandEnd)
  }

  /**
   * The only check left that ROR read the right span, now that the span comes from ES itself. Normalizing the way
   * `IdentifierBuilder.visitIndexPattern` does has to bring the written text back to what ES reported.
   */
  private def checkMatchesEsReport(
      query: ClassifiedQuery,
      indexListSpan: QueryTextSpan,
      reportedIndexList: String
  ): Either[EsqlIndexListReadingFailure, Unit] = {
    val written = normalizedEntriesIn(query, indexListSpan)
    Either.cond(
      test = written == reportedIndexList,
      right = (),
      left = EsqlIndexListReadingFailure.DoesNotMatchEsReport(written, reportedIndexList)
    )
  }

  private def normalizedEntriesIn(query: ClassifiedQuery, span: QueryTextSpan): String = {
    query
      .topLevelCommaSeparatedIn(span)
      .map(unquoted)
      .map(_.trim)
      .filter(_.nonEmpty)
      .mkString(",")
  }

  private def unquoted(text: String): String = {
    if (text.length >= 6 && text.startsWith("\"\"\"") && text.endsWith("\"\"\"")) text.substring(3, text.length - 3)
    else if (text.length >= 2 && text.startsWith("\"") && text.endsWith("\"")) text.substring(1, text.length - 1)
    else text
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

  private sealed trait CharClass

  private object CharClass {
    case object Code extends CharClass
    case object Comment extends CharClass
    case object Quoted extends CharClass
  }

  /** Tells code apart from literals and comments, so an index name written in either is never taken for a real one. */
  private final class ClassifiedQuery private (text: String, charClasses: IndexedSeq[CharClass]) {

    val length: Int = text.length

    def charAt(idx: Int): Char = text.charAt(idx)

    def isCode(idx: Int): Boolean = idx >= 0 && idx < length && charClasses(idx) == CharClass.Code

    def isComment(idx: Int): Boolean = idx >= 0 && idx < length && charClasses(idx) == CharClass.Comment

    def textIn(span: QueryTextSpan): String = text.substring(span.start, span.end)

    def wordAt(idx: Int): String = if (idx < length) text.substring(idx, endOfWord(idx)) else ""

    def indexOfNewLineFrom(idx: Int): Option[Int] = Option(text.indexOf('\n', idx)).filter(_ >= 0)

    def topLevelCommaSeparatedIn(span: QueryTextSpan): List[String] = {
      val separators = span.start.until(span.end).filter(idx => isCode(idx) && charAt(idx) == ',').toList
      val starts = span.start :: separators.map(_ + 1)
      val ends = separators :+ span.end
      starts.zip(ends).map { case (from, until) => textInWithoutComments(QueryTextSpan(from, until)).trim }
    }

    /**
     * ES lexes an index pattern as a single token, so `app-metadata` holds no `METADATA` keyword - only a blank (or
     * a comment, which ES hides on its own channel) tells the keyword apart from the pattern.
     */
    def isSeparateWordStart(idx: Int): Boolean =
      isWordChar(charAt(idx)) && idx > 0 && (charAt(idx - 1).isWhitespace || isComment(idx - 1))

    @tailrec
    final def firstNonBlankFrom(idx: Int): Int = {
      if (idx >= length) length
      else if (charAt(idx).isWhitespace || isComment(idx)) firstNonBlankFrom(idx + 1)
      else idx
    }

    @tailrec
    final def lastNonSeparatorIn(from: Int, until: Int): Int = {
      if (until <= from) until
      else if (charAt(until - 1).isWhitespace || charAt(until - 1) == ',' || isComment(until - 1))
        lastNonSeparatorIn(from, until - 1)
      else until
    }

    /** Comments are legal between entries, and blanking rather than dropping them keeps the entries around intact. */
    private def textInWithoutComments(span: QueryTextSpan): String =
      span.start.until(span.end).map(idx => if (isComment(idx)) ' ' else charAt(idx)).mkString

    @tailrec
    private def endOfWord(idx: Int): Int = if (idx < length && isWordChar(charAt(idx))) endOfWord(idx + 1) else idx

    private def isWordChar(char: Char): Boolean = char.isLetterOrDigit || char == '_'
  }

  private object ClassifiedQuery {

    def of(text: String): ClassifiedQuery = new ClassifiedQuery(text, charClassesOf(text))

    @tailrec
    private def charClassesOf(
        text: String,
        from: Int = 0,
        classified: Vector[CharClass] = Vector.empty
    ): Vector[CharClass] = {
      if (from >= text.length) {
        classified
      } else {
        val run = runAt(text, from)
        charClassesOf(text, run.end, classified ++ Vector.fill(run.end - from)(run.charClass))
      }
    }

    private def runAt(text: String, from: Int): ClassifiedRun = {
      if (text.startsWith("//", from))
        ClassifiedRun(CharClass.Comment, endOfRun(text, from + 2, "\n", keepCloser = false))
      else if (text.startsWith("/*", from))
        ClassifiedRun(CharClass.Comment, endOfRun(text, from + 2, "*/", keepCloser = true))
      else if (text.startsWith("\"\"\"", from))
        ClassifiedRun(CharClass.Quoted, endOfRun(text, from + 3, "\"\"\"", keepCloser = true))
      else if (text.charAt(from) == '"') ClassifiedRun(CharClass.Quoted, endOfQuotedRun(text, from + 1, '"'))
      else if (text.charAt(from) == '`') ClassifiedRun(CharClass.Quoted, endOfQuotedRun(text, from + 1, '`'))
      else ClassifiedRun(CharClass.Code, from + 1)
    }

    private def endOfRun(text: String, searchFrom: Int, closer: String, keepCloser: Boolean): Int = {
      text.indexOf(closer, searchFrom) match {
        case -1      => text.length
        case closeAt => if (keepCloser) closeAt + closer.length else closeAt
      }
    }

    @tailrec
    private def endOfQuotedRun(text: String, idx: Int, closer: Char): Int = {
      if (idx >= text.length) text.length
      else if (text.charAt(idx) == '\\') endOfQuotedRun(text, idx + 2, closer)
      else if (text.charAt(idx) == closer) idx + 1
      else endOfQuotedRun(text, idx + 1, closer)
    }

    private final case class ClassifiedRun(charClass: CharClass, end: Int)
  }

}
