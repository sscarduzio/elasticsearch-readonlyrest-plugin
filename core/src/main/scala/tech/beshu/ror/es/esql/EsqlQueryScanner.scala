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

import scala.annotation.tailrec

final case class QueryTextSpan(start: Int, end: Int)

/**
 * Subquery entries split a list across several spans: for `FROM a, (FROM b), c` ES reports `a,c`, whose entries sit
 * at two places in the text. Every span but the first opens with its separating comma, so dropping it leaves no
 * dangling separator behind.
 */
final case class WrittenIndexList(spans: NonEmptyList[QueryTextSpan], text: NormalizedIndexList)

/**
 * A `PROMQL` command that names no `index=` parameter reads the index pattern ES defaults to, which is written
 * nowhere in the query - there is no span to narrow, so such a query has to be rejected instead.
 */
final case class WrittenIndexLists(
    ofSourceCommands: List[WrittenIndexList],
    ofLookupJoins: List[WrittenIndexList],
    hasPromqlLeaningOnDefaultIndex: Boolean
)

/**
 * Searching the query for the text ES reported cannot replace this scan: ES reports it normalized (`FROM a, b` as
 * `a,b`), so the search silently finds nothing and leaves the user's own indices in the query.
 *
 * A `(` opens a source command only inside a source command's index list, where the grammar allows a subquery;
 * treating every `(` that way makes `WHERE (ts > ...)` look like the `TS` command. Elsewhere a `(` can still open
 * a `FORK` branch, whose first command may be a `LOOKUP JOIN`.
 */
private[esql] object EsqlQueryScanner {

  private val sourceCommandKeywords = Set("FROM", "TS")
  private val promqlKeyword = "PROMQL"
  private val promqlIndexParam = "index"
  private val metadataKeyword = "METADATA"
  private val joinKeyword = "JOIN"
  private val joinConditionKeyword = "ON"
  private val lookupKeyword = "LOOKUP"

  def indexListsWrittenIn(query: EsqlQuery): WrittenIndexLists = {
    val classifiedQuery = ClassifiedQuery.of(query)

    @tailrec
    def scan(
        idx: Int,
        state: ScanState,
        openBrackets: List[OpenBracket],
        found: FoundSoFar
    ): WrittenIndexLists = {
      state match {
        case ScanState.AtIndexListEntry(indexList) =>
          val entries = entriesFrom(classifiedQuery, idx, endKeyword = metadataKeyword)
          val grownIndexList = indexList.and(entries.span)
          entries.end match {
            case EntriesEnd.SubqueryEntryOpens(at) =>
              val openBracket = OpenBracket.SubqueryEntry(grownIndexList)
              scan(at + 1, ScanState.atNextCommand, openBracket :: openBrackets, found)
            case EntriesEnd.ListEnds(at) =>
              scan(at, ScanState.midCommand, openBrackets, found.plusSourceCommand(grownIndexList, classifiedQuery))
          }
        case ScanState.AfterSubqueryEntry(indexList) =>
          val commaAt = classifiedQuery.firstNonBlankFrom(idx)
          if (classifiedQuery.isCodeChar(commaAt, ',')) {
            // the first span is written into by the rewrite, so it must not swallow a comma
            val nextEntryAt = if (indexList.isEmpty) commaAt + 1 else commaAt
            scan(nextEntryAt, ScanState.AtIndexListEntry(indexList), openBrackets, found)
          } else {
            scan(idx, ScanState.midCommand, openBrackets, found.plusSourceCommand(indexList, classifiedQuery))
          }
        case ScanState.InPipeline(expectedCommand) =>
          if (idx >= classifiedQuery.length) {
            found.plusUnclosedSubqueriesIn(openBrackets, classifiedQuery).indexLists
          } else if (!classifiedQuery.isCode(idx) || classifiedQuery.charAt(idx).isWhitespace) {
            scan(idx + 1, state, openBrackets, found)
          } else if (isCommandBoundary(classifiedQuery.charAt(idx))) {
            scan(idx + 1, ScanState.atNextCommand, openBrackets, found)
          } else if (classifiedQuery.charAt(idx) == '(') {
            scan(idx + 1, ScanState.inForkBranch, OpenBracket.Grouping :: openBrackets, found)
          } else if (classifiedQuery.charAt(idx) == ')') {
            openBrackets match {
              case OpenBracket.SubqueryEntry(indexList) :: stillOpen =>
                scan(idx + 1, ScanState.AfterSubqueryEntry(indexList), stillOpen, found)
              case _ :: stillOpen =>
                scan(idx + 1, ScanState.midCommand, stillOpen, found)
              case Nil =>
                scan(idx + 1, ScanState.midCommand, Nil, found)
            }
          } else {
            commandAt(classifiedQuery, idx, expectedCommand) match {
              case Command.SourceCommand(indexListAt) =>
                scan(indexListAt, ScanState.AtIndexListEntry(IndexListUnderScan.empty), openBrackets, found)
              case Command.LookupJoin(target) =>
                scan(target.spans.head.end, ScanState.midCommand, openBrackets, found.plusLookupJoin(target))
              case Command.PromqlCommand(indexParam, continueAt) =>
                scan(continueAt, ScanState.midCommand, openBrackets, found.plusPromqlCommand(indexParam))
              case Command.SomethingElse(continueAt) =>
                scan(continueAt, ScanState.midCommand, openBrackets, found)
            }
          }
      }
    }

    scan(0, ScanState.atNextCommand, List.empty, FoundSoFar.nothing)
  }

  /** `;` ends the `SET` prelude, so the command after it opens a pipeline just like the one after a `|` does. */
  private def isCommandBoundary(char: Char): Boolean = char == '|' || char == ';'

  private def commandAt(query: ClassifiedQuery, idx: Int, expected: ExpectedCommand): Command = {
    val word = query.wordAt(idx)
    val afterWord = idx + Math.max(word.length, 1)
    if (expected == ExpectedCommand.AnyCommand && sourceCommandKeywords.exists(_.equalsIgnoreCase(word))) {
      Command.SourceCommand(indexListAt = afterWord)
    } else if (expected == ExpectedCommand.AnyCommand && word.equalsIgnoreCase(promqlKeyword)) {
      promqlIndexParamAfter(query, afterWord) match {
        case Some(span) => Command.PromqlCommand(Some(writtenIndexList(query, NonEmptyList.one(span))), span.end)
        case None       => Command.PromqlCommand(None, continueAt = afterWord)
      }
    } else if (word.equalsIgnoreCase(lookupKeyword)) {
      lookupJoinTargetAfter(query, afterWord)
        .map(Command.LookupJoin.apply)
        .getOrElse(Command.SomethingElse(continueAt = afterWord))
    } else {
      Command.SomethingElse(continueAt = afterWord)
    }
  }

  private def lookupJoinTargetAfter(query: ClassifiedQuery, afterLookup: Int): Option[WrittenIndexList] = {
    keywordAt(query, afterLookup, joinKeyword)
      .flatMap(afterJoin => entriesFrom(query, afterJoin, endKeyword = joinConditionKeyword).span)
      .map(span => writtenIndexList(query, NonEmptyList.one(span)))
  }

  /**
   * `PROMQL` takes its indices in a `name=value` parameter rather than in an index list, and the PromQL expression
   * the parameters are followed by may hold `==` - which is no assignment, so it ends the parameter walk.
   */
  @tailrec
  private def promqlIndexParamAfter(query: ClassifiedQuery, from: Int): Option[QueryTextSpan] = {
    val nameAt = query.firstNonBlankFrom(from)
    val name = if (nameAt < query.length && query.isCode(nameAt)) query.wordAt(nameAt) else ""
    val assignAt = query.firstNonBlankFrom(nameAt + name.length)
    if (name.isEmpty || !query.isCodeChar(assignAt, '=') || query.isCodeChar(assignAt + 1, '=')) {
      None
    } else {
      val valueSpan = promqlParamValueAfter(query, assignAt + 1)
      if (name.equalsIgnoreCase(promqlIndexParam)) Some(valueSpan)
      else promqlIndexParamAfter(query, valueSpan.end)
    }
  }

  private def promqlParamValueAfter(query: ClassifiedQuery, from: Int): QueryTextSpan = {
    val start = query.firstNonBlankFrom(from)
    QueryTextSpan(start, endOfPromqlParamValue(query, start))
  }

  @tailrec
  private def endOfPromqlParamValue(query: ClassifiedQuery, idx: Int): Int = {
    if (idx >= query.length) query.length
    else if (query.isComment(idx)) idx
    else if (!query.isCode(idx)) endOfPromqlParamValue(query, idx + 1)
    else if (query.charAt(idx).isWhitespace || isCommandBoundary(query.charAt(idx))) idx
    else endOfPromqlParamValue(query, idx + 1)
  }

  private def entriesFrom(query: ClassifiedQuery, from: Int, endKeyword: String): ScannedEntries = {
    val start = query.firstNonBlankFrom(from)
    val stoppedAt = endOfEntries(query, listStart = start, idx = start, endKeyword)
    val end = query.lastNonSeparatorIn(from = start, until = stoppedAt)
    ScannedEntries(
      span = Option.when(end > start)(QueryTextSpan(start, end)),
      end =
        if (query.isCodeChar(stoppedAt, '(')) EntriesEnd.SubqueryEntryOpens(stoppedAt)
        else EntriesEnd.ListEnds(stoppedAt)
    )
  }

  /** `[` ends a list: it opens the bracketed `METADATA` clause, the only form ES 8.11 and 8.12 accept. */
  @tailrec
  private def endOfEntries(query: ClassifiedQuery, listStart: Int, idx: Int, endKeyword: String): Int = {
    if (idx >= query.length) query.length
    else if (!query.isCode(idx)) endOfEntries(query, listStart, idx + 1, endKeyword)
    else if ("()[]".contains(query.charAt(idx)) || isCommandBoundary(query.charAt(idx))) idx
    else if (idx > listStart && query.isSeparateWordStart(idx) && query.wordAt(idx).equalsIgnoreCase(endKeyword)) idx
    else endOfEntries(query, listStart, idx + 1, endKeyword)
  }

  private def keywordAt(query: ClassifiedQuery, from: Int, keyword: String): Option[Int] = {
    val start = query.firstNonBlankFrom(from)
    Option
      .when(query.isCode(start))(query.wordAt(start))
      .filter(_.equalsIgnoreCase(keyword))
      .map(start + _.length)
  }

  private def writtenIndexList(query: ClassifiedQuery, spans: NonEmptyList[QueryTextSpan]): WrittenIndexList =
    WrittenIndexList(spans, indexListTextOf(query, spans))

  /** Mirrors how ES's `IdentifierBuilder.visitIndexPattern` normalizes what it reports back to us. */
  private def indexListTextOf(query: ClassifiedQuery, spans: NonEmptyList[QueryTextSpan]): NormalizedIndexList =
    NormalizedIndexList.normalizedFromQueryText {
      spans.toList.flatMap(span => entriesWrittenIn(query.textIn(span))).mkString(",")
    }

  private def entriesWrittenIn(text: String): List[String] = {
    val classified = ClassifiedQuery.of(EsqlQuery(text))
    splitTopLevelByComma(classified)
      .map(unquoted)
      .flatMap(_.split(',').toList)
      .map(_.trim)
      .filter(_.nonEmpty)
  }

  private def splitTopLevelByComma(query: ClassifiedQuery): List[String] = {
    val separators = (0 until query.length).filter(idx => query.isCodeChar(idx, ',')).toList
    val starts = 0 :: separators.map(_ + 1)
    val ends = separators :+ query.length
    starts.zip(ends).map { case (from, until) => query.textInWithoutComments(QueryTextSpan(from, until)).trim }
  }

  private def unquoted(text: String): String = {
    if (text.length >= 6 && text.startsWith("\"\"\"") && text.endsWith("\"\"\"")) text.substring(3, text.length - 3)
    else if (text.length >= 2 && text.startsWith("\"") && text.endsWith("\"")) text.substring(1, text.length - 1)
    else text
  }

  private sealed trait ScanState

  private object ScanState {

    final case class InPipeline(expectedCommand: ExpectedCommand) extends ScanState

    final case class AtIndexListEntry(indexList: IndexListUnderScan) extends ScanState

    final case class AfterSubqueryEntry(indexList: IndexListUnderScan) extends ScanState

    val atNextCommand: ScanState = InPipeline(ExpectedCommand.AnyCommand)
    val inForkBranch: ScanState = InPipeline(ExpectedCommand.ProcessingCommandOnly)
    val midCommand: ScanState = InPipeline(ExpectedCommand.NoCommand)
  }

  private sealed trait ExpectedCommand

  private object ExpectedCommand {

    case object AnyCommand extends ExpectedCommand

    case object ProcessingCommandOnly extends ExpectedCommand

    case object NoCommand extends ExpectedCommand
  }

  private sealed trait Command

  private object Command {
    final case class SourceCommand(indexListAt: Int) extends Command
    final case class LookupJoin(target: WrittenIndexList) extends Command
    final case class PromqlCommand(indexParam: Option[WrittenIndexList], continueAt: Int) extends Command
    final case class SomethingElse(continueAt: Int) extends Command
  }

  private sealed trait OpenBracket

  private object OpenBracket {

    case object Grouping extends OpenBracket

    final case class SubqueryEntry(interruptedIndexList: IndexListUnderScan) extends OpenBracket
  }

  private final case class ScannedEntries(span: Option[QueryTextSpan], end: EntriesEnd)

  private sealed trait EntriesEnd

  private object EntriesEnd {
    final case class SubqueryEntryOpens(at: Int) extends EntriesEnd
    final case class ListEnds(at: Int) extends EntriesEnd
  }

  private final class IndexListUnderScan private (reversedSpans: List[QueryTextSpan]) {

    def isEmpty: Boolean = reversedSpans.isEmpty

    def and(span: Option[QueryTextSpan]): IndexListUnderScan =
      new IndexListUnderScan(span.toList ::: reversedSpans)

    def written(query: ClassifiedQuery): Option[WrittenIndexList] =
      NonEmptyList.fromList(reversedSpans.reverse).map(writtenIndexList(query, _))
  }

  private object IndexListUnderScan {
    val empty: IndexListUnderScan = new IndexListUnderScan(List.empty)
  }

  private final case class FoundSoFar(
      reversedSourceCommands: List[WrittenIndexList],
      reversedLookupJoins: List[WrittenIndexList],
      promqlLeaningOnDefaultIndex: Boolean
  ) {

    def plusSourceCommand(indexList: IndexListUnderScan, query: ClassifiedQuery): FoundSoFar =
      copy(reversedSourceCommands = indexList.written(query).toList ::: reversedSourceCommands)

    def plusLookupJoin(target: WrittenIndexList): FoundSoFar =
      copy(reversedLookupJoins = target :: reversedLookupJoins)

    /** ES reports the `PROMQL` target in the same field as a source command's, so it is narrowed as one. */
    def plusPromqlCommand(indexParam: Option[WrittenIndexList]): FoundSoFar =
      indexParam match {
        case Some(indexList) => copy(reversedSourceCommands = indexList :: reversedSourceCommands)
        case None            => copy(promqlLeaningOnDefaultIndex = true)
      }

    /** ES rejects an unbalanced `(` before we see it; reporting what it opened keeps the mismatch check erring towards rejection. */
    def plusUnclosedSubqueriesIn(openBrackets: List[OpenBracket], query: ClassifiedQuery): FoundSoFar =
      openBrackets
        .collect { case OpenBracket.SubqueryEntry(indexList) => indexList }
        .foldLeft(this)((found, indexList) => found.plusSourceCommand(indexList, query))

    def indexLists: WrittenIndexLists =
      WrittenIndexLists(reversedSourceCommands.reverse, reversedLookupJoins.reverse, promqlLeaningOnDefaultIndex)
  }

  private object FoundSoFar {
    val nothing: FoundSoFar = FoundSoFar(List.empty, List.empty, promqlLeaningOnDefaultIndex = false)
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

    def isCodeChar(idx: Int, char: Char): Boolean = isCode(idx) && charAt(idx) == char

    def isComment(idx: Int): Boolean = idx >= 0 && idx < length && charClasses(idx) == CharClass.Comment

    def textIn(span: QueryTextSpan): String = text.substring(span.start, span.end)

    /** Comments are legal between entries, and blanking rather than dropping them keeps the entries around intact. */
    def textInWithoutComments(span: QueryTextSpan): String =
      span.start.until(span.end).map(idx => if (isComment(idx)) ' ' else charAt(idx)).mkString

    def wordAt(idx: Int): String = text.substring(idx, endOfWord(idx))

    /**
     * ES lexes an index pattern as a single token, so `app-metadata` holds no `METADATA` keyword and `ref-on` no
     * `ON` - only a blank (or a comment, which ES hides on its own channel) tells the keyword apart from the pattern.
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

    @tailrec
    private def endOfWord(idx: Int): Int = if (idx < length && isWordChar(charAt(idx))) endOfWord(idx + 1) else idx

    private def isWordChar(char: Char): Boolean = char.isLetterOrDigit || char == '_'
  }

  private object ClassifiedQuery {

    def of(query: EsqlQuery): ClassifiedQuery = new ClassifiedQuery(query.value, charClassesOf(query.value))

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
