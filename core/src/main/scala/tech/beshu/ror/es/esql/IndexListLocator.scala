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
import tech.beshu.ror.es.esql.EsqlQueryIndicesReader.{IndexPatternInQuery, QueryIndices, SourceLocation}
import tech.beshu.ror.es.esql.LocatedIndexList.{LookupJoinTarget, SourceCommandIndices}

import scala.annotation.tailrec
import scala.util.Try
import scala.util.matching.Regex

/**
 * ES reports an index list normalized (`FROM a, b` as `a,b`), so it cannot be searched for in the query - only the
 * source location ES keeps next to it turns it into a span to rewrite. A `LOOKUP JOIN` target is located exactly;
 * a source command is located whole (`FROM a, b METADATA _index`), so its list is picked out of the command text
 * literally - and either way, only once the span reads back as the list ES reported. A `PROMQL` command that writes
 * no `index` parameter holds no list at all, so ROR writes the parameter itself, after the keyword.
 */
private[esql] object IndexListLocator {

  /**
   * Everything between the command keyword and a `METADATA` clause, in either the current or the ES 8.x form. The
   * list has to end on a character that could close an index name, or `FROM a, metadata` would read its last index
   * as the start of the clause.
   */
  private val sourceCommandIndexList: Regex =
    """(?is)^\s*(?:FROM|TS)\s+(.*?[^\s,])(?:\s+METADATA\b.*|\s*\[\s*METADATA\b.*|\s*)$""".r

  private val promqlKeyword: Regex = """(?is)^\s*PROMQL\b""".r

  /** A parameter ES binds by its name (`?index`) or by its position (`?1`) - neither tied to where it is written. */
  private val boundQueryParameter: Regex = """^\s*\?\??[A-Za-z_0-9]+\s*$""".r

  /** A parameter ES binds by the order the placeholders appear in, so dropping one rebinds every one after it. */
  private val anonymousQueryParameter: Regex = """^\s*\?\??\s*$""".r

  def locatedIn(query: String, indices: QueryIndices): Either[ReadingFailure, List[LocatedIndexList]] = {
    val relations = indices.withoutRepeats
    for {
      fromSources <- relations.fromSources.traverse(locateFromSource(query, _))
      lookupJoins <- relations.lookupJoins.traverse(locateLookupJoin(query, _))
      indexLists = fromSources ++ lookupJoins
      _ <- checkNoneOverlaps(query, indexLists)
    } yield indexLists
  }

  private def checkNoneOverlaps(query: String, indexLists: List[LocatedIndexList]): Either[ReadingFailure, Unit] = {
    indexLists
      .map(_.span)
      .sortBy(span => (span.start, span.end))
      .sliding(2)
      .collectFirst {
        case List(one, next) if next.start < one.end || next.start == one.start =>
          ReadingFailure.OverlappingIndexLists(
            query.substring(one.start, one.end),
            query.substring(next.start, next.end)
          )
      }
      .toLeft(())
  }

  private def locateFromSource(
      query: String,
      pattern: IndexPatternInQuery
  ): Either[ReadingFailure, LocatedIndexList] = {
    val commandText = withoutComments(pattern.writtenText)
    for {
      writtenSpan <- writtenSpanOf(query, pattern)
      place <- fromSourcePlaceIn(pattern, commandText, writtenSpan)
      locatedIndexList <- place match {
        case IndexListPlace.InQueryText(span, text) =>
          checkHoldsReportedIndexPattern(pattern, text)
            .flatMap(_ => sourceCommandIndicesAt(span, pattern, IndexListSyntax.BareIndexList))
        case IndexListPlace.PromqlIndexParameterToWrite(span) =>
          sourceCommandIndicesAt(span, pattern, IndexListSyntax.PromqlIndexParameter)
      }
    } yield locatedIndexList
  }

  private def locateLookupJoin(
      query: String,
      pattern: IndexPatternInQuery
  ): Either[ReadingFailure, LocatedIndexList] =
    for {
      writtenSpan <- writtenSpanOf(query, pattern)
      _ <- checkHoldsReportedIndexPattern(pattern, withoutComments(pattern.writtenText))
      target <- LookupJoinTarget
        .parse(writtenSpan, pattern.reportedIndexList)
        .toRight(ReadingFailure.UnsupportedIndexList(pattern.reportedIndexList))
    } yield target

  private def sourceCommandIndicesAt(
      span: TextSpan,
      pattern: IndexPatternInQuery,
      syntax: IndexListSyntax
  ): Either[ReadingFailure, LocatedIndexList] =
    SourceCommandIndices
      .parse(span, pattern.reportedIndexList, syntax)
      .toRight(ReadingFailure.UnsupportedIndexList(pattern.reportedIndexList))

  private def writtenSpanOf(
      query: String,
      pattern: IndexPatternInQuery
  ): Either[ReadingFailure, TextSpan] = {
    offsetOf(query, pattern.writtenAt)
      .map(start => TextSpan(start, start + pattern.writtenText.length))
      .filter(span => span.end <= query.length && query.substring(span.start, span.end) == pattern.writtenText)
      .toRight(ReadingFailure.NotWhereEsReportedIt(pattern.reportedIndexList))
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
      // ES|QL's ANTLR stream counts the column in code points, not in the UTF-16 units a Scala string indexes by
      .flatMap(lineStart => Try(query.offsetByCodePoints(lineStart, location.column)).toOption)
  }

  private def fromSourcePlaceIn(
      pattern: IndexPatternInQuery,
      commandText: String,
      writtenSpan: TextSpan
  ): Either[ReadingFailure, IndexListPlace] = {
    sourceCommandIndexList.findFirstMatchIn(commandText) match {
      case Some(indexList) =>
        val span = TextSpan(writtenSpan.start + indexList.start(1), writtenSpan.start + indexList.end(1))
        Either.cond(
          // a subquery entry is merged into the reported list, leaving it no span of its own
          test = !indexList.group(1).contains('('),
          right = IndexListPlace.InQueryText(span, indexList.group(1)),
          left = ReadingFailure.SubqueryInSourceCommand(pattern.reportedIndexList)
        )
      case None =>
        // a `PROMQL` command's `index=` parameter, whose value ES locates exactly, unless the query writes none
        Right(
          promqlIndexParameterToWrite(commandText, writtenSpan, pattern)
            .getOrElse(IndexListPlace.InQueryText(writtenSpan, commandText))
        )
    }
  }

  /**
   * A `PROMQL` command with no `index` parameter reads whichever indices ES picks, so ES points at the whole command
   * instead of at an index list. The parameter ROR writes in its place goes right after the keyword, since every
   * parameter stands before the expression the command ends with.
   */
  private def promqlIndexParameterToWrite(
      commandText: String,
      writtenSpan: TextSpan,
      pattern: IndexPatternInQuery
  ): Option[IndexListPlace.PromqlIndexParameterToWrite] = {
    Option
      .when(!sameIndexList(commandText, pattern.reportedIndexList))(commandText)
      .flatMap(promqlKeyword.findFirstMatchIn)
      .map { keyword =>
        val writeAt = writtenSpan.start + keyword.end
        IndexListPlace.PromqlIndexParameterToWrite(TextSpan(writeAt, writeAt))
      }
  }

  /**
   * ES reports the list it read, not the text it read it from, so a span is only safe to rewrite once it reads back
   * as that list - a named or positional parameter excepted, since its value is not written in the query at all.
   */
  private def checkHoldsReportedIndexPattern(
      pattern: IndexPatternInQuery,
      spanText: String
  ): Either[ReadingFailure, Unit] = {
    if (sameIndexList(spanText, pattern.reportedIndexList) || boundQueryParameter.matches(spanText)) Right(())
    else if (anonymousQueryParameter.matches(spanText)) Left(ReadingFailure.IndexListInAnonymousParameter)
    else Left(ReadingFailure.NotWhereEsReportedIt(pattern.reportedIndexList))
  }

  /**
   * Compared with the quoting and the spacing dropped from both sides: ES hides whitespace anywhere inside a source
   * command (`FROM remote : idx`) and reports the list unquoted, neither of which makes it a different list.
   */
  private def sameIndexList(one: String, other: String): Boolean =
    quotingAndSpacingAside(one) == quotingAndSpacingAside(other)

  private def quotingAndSpacingAside(indexList: String): String =
    indexList.filterNot(char => char.isWhitespace || char == '"')

  /** Blanked, not dropped, so what is left keeps its offsets. */
  private def withoutComments(commandText: String): String = {
    @tailrec
    def blanked(at: Int, acc: StringBuilder): String = {
      if (at >= commandText.length) acc.result()
      else
        endOfCommentAt(commandText, at) match {
          case Some(end) => blanked(end, acc.append(" " * (end - at)))
          case None      => blanked(at + 1, acc.append(commandText.charAt(at)))
        }
    }
    blanked(0, new StringBuilder(commandText.length))
  }

  private def endOfCommentAt(text: String, at: Int): Option[Int] = {
    if (text.startsWith("//", at)) Some(endOfLineComment(text, at))
    else if (text.startsWith("/*", at)) Some(endOfBlockComment(text, at + 2, depth = 1))
    else None
  }

  private def endOfLineComment(text: String, at: Int): Int =
    text.indexOf('\n', at) match {
      case -1  => text.length
      case end => end
    }

  /** ES lets a block comment nest, so the one opened here closes only where none is left open. */
  @tailrec
  private def endOfBlockComment(text: String, at: Int, depth: Int): Int = {
    if (depth == 0 || at >= text.length) at
    else if (text.startsWith("*/", at)) endOfBlockComment(text, at + 2, depth - 1)
    else if (text.startsWith("/*", at)) endOfBlockComment(text, at + 2, depth + 1)
    else endOfBlockComment(text, at + 1, depth)
  }

  private sealed trait IndexListPlace

  private object IndexListPlace {

    final case class InQueryText(span: TextSpan, text: String) extends IndexListPlace

    final case class PromqlIndexParameterToWrite(span: TextSpan) extends IndexListPlace
  }

}
