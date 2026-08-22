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

import cats.Show
import cats.data.NonEmptyList
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestedIndex}
import tech.beshu.ror.es.esql.EsqlIndexTable.{LookupJoin, SourceCommand}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*

object EsqlQueryNarrowing {

  /** Narrowing is all-or-nothing: a partial rewrite would leave some of the user's own indices in the query. */
  final case class IndexListsMismatch(
      commandKind: EsqlCommandKind,
      reportedButNotWrittenInQuery: Set[NormalizedIndexList],
      writtenInQueryButNotReported: Set[NormalizedIndexList]
  ) extends EsqlNarrowingFailure

  case object PromqlLeaningOnDefaultIndex extends EsqlNarrowingFailure

  object IndexListsMismatch {

    implicit val show: Show[IndexListsMismatch] = Show.show { failure =>
      s"the ${failure.commandKind.show} of the query do not line up with the ones ES reported for it " +
        s"[reported but not found in the query: ${failure.reportedButNotWrittenInQuery.show}] " +
        s"[found in the query but not reported: ${failure.writtenInQueryButNotReported.show}]"
    }

  }

  def narrowedQuery(
      query: EsqlQuery,
      tables: NonEmptyList[EsqlIndexTable],
      allowedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
  ): Either[EsqlNarrowingFailure, EsqlQuery] = {
    val newIndexLists = newIndexListsFor(tables, allowedIndices)
    val writtenIndexLists = EsqlQueryScanner.indexListsWrittenIn(query)
    for {
      _ <- Either.cond(!writtenIndexLists.hasPromqlLeaningOnDefaultIndex, (), PromqlLeaningOnDefaultIndex)
      sourceCommandEdits <- editsFor(
        EsqlCommandKind.SourceCommand,
        writtenIndexLists.ofSourceCommands,
        newIndexLists.ofSourceCommands
      )
      lookupJoinEdits <- editsFor(
        EsqlCommandKind.LookupJoin,
        writtenIndexLists.ofLookupJoins,
        newIndexLists.ofLookupJoins
      )
    } yield rewritten(query, sourceCommandEdits ::: lookupJoinEdits)
  }

  private def newIndexListsFor(
      tables: NonEmptyList[EsqlIndexTable],
      allowedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
  ): NewIndexLists = {
    val allowedIndexNames: Set[ClusterIndexName] = allowedIndices.includedOnly

    val (lookupJoins, sourceCommands) = tables.toList.partitionMap {
      case table: LookupJoin    => Left(table)
      case table: SourceCommand => Right(table)
    }

    val reachableOnlyThroughLookupJoin: Set[ClusterIndexName] =
      lookupJoins.map(_.index).toCovariantSet -- sourceCommands.flatMap(_.requestedIndices.includedOnly).toCovariantSet

    val scope =
      if (sourceCommands.sizeIs == 1) SourceCommandScope.TheOnlyOne else SourceCommandScope.OneOfSeveral

    NewIndexLists(
      ofSourceCommands = sourceCommands.map { table =>
        (
          table.indexListText,
          NormalizedIndexList.of(narrowed(table, allowedIndexNames, reachableOnlyThroughLookupJoin, scope))
        )
      }.toMap,
      ofLookupJoins = lookupJoins.map { table =>
        (table.indexListText, NormalizedIndexList.of(narrowed(table, allowedIndexNames)))
      }.toMap
    )
  }

  /**
   * A lone source command gets the whole ACL-resolved set, because a rule may resolve a request to indices the
   * written pattern never matched (`FROM bookshop` under `indices: ["bookstore"]`). That set cannot be attributed
   * to any single command once there are several, where handing it to each would answer a subquery the ACL denied
   * with another command's rows.
   */
  private def narrowed(
      table: SourceCommand,
      allowedIndices: Set[ClusterIndexName],
      reachableOnlyThroughLookupJoin: Set[ClusterIndexName],
      scope: SourceCommandScope
  ): NonEmptyList[ClusterIndexName] = {
    val namesMatchingWrittenPattern = table.writtenPattern.filter(allowedIndices)
    val names = scope match {
      case SourceCommandScope.TheOnlyOne =>
        (allowedIndices -- reachableOnlyThroughLookupJoin) ++ namesMatchingWrittenPattern
      case SourceCommandScope.OneOfSeveral =>
        namesMatchingWrittenPattern
    }
    NonEmptyList.fromList(names.toList).getOrElse(maskedAsNonexistent)
  }

  private def narrowed(table: LookupJoin, allowedIndices: Set[ClusterIndexName]): NonEmptyList[ClusterIndexName] =
    if (allowedIndices.contains(table.index)) NonEmptyList.one(table.index) else maskedAsNonexistent

  private def maskedAsNonexistent: NonEmptyList[ClusterIndexName] =
    NonEmptyList.one(ClusterIndexName.Local.randomNonexistentIndex())

  private def editsFor(
      commandKind: EsqlCommandKind,
      writtenIndexLists: List[WrittenIndexList],
      newIndexListByReportedOne: Map[NormalizedIndexList, NormalizedIndexList]
  ): Either[EsqlNarrowingFailure, List[Edit]] = {
    val reported = newIndexListByReportedOne.keySet.toCovariantSet
    val written = writtenIndexLists.map(_.text).toCovariantSet
    Either.cond(
      test = reported == written,
      right = writtenIndexLists.flatMap(list => newIndexListByReportedOne.get(list.text).map(Edit(list, _))),
      left = IndexListsMismatch(commandKind, reported -- written, written -- reported)
    )
  }

  private def rewritten(query: EsqlQuery, edits: List[Edit]): EsqlQuery = {
    val replacements = edits.flatMap { edit =>
      TextReplacement(edit.writtenIndexList.spans.head, edit.newIndexList.value) ::
        edit.writtenIndexList.spans.tail.map(TextReplacement(_, ""))
    }
    EsqlQuery(
      replacements.sortBy(-_.span.start).foldLeft(query.value) { case (text, replacement) =>
        s"${text.substring(0, replacement.span.start)}${replacement.newText}${text.substring(replacement.span.end)}"
      }
    )
  }

  private final case class NewIndexLists(
      ofSourceCommands: Map[NormalizedIndexList, NormalizedIndexList],
      ofLookupJoins: Map[NormalizedIndexList, NormalizedIndexList]
  )

  private final case class Edit(writtenIndexList: WrittenIndexList, newIndexList: NormalizedIndexList)

  private final case class TextReplacement(span: QueryTextSpan, newText: String)

  private sealed trait SourceCommandScope

  private object SourceCommandScope {
    case object TheOnlyOne extends SourceCommandScope
    case object OneOfSeveral extends SourceCommandScope
  }

}

sealed trait EsqlNarrowingFailure

object EsqlNarrowingFailure {

  implicit val show: Show[EsqlNarrowingFailure] = Show.show {
    case mismatch: EsqlQueryNarrowing.IndexListsMismatch => mismatch.show
    case EsqlQueryNarrowing.PromqlLeaningOnDefaultIndex  =>
      "the PROMQL command names no [index] parameter, so the indices it reads are the ones ES defaults to and " +
        "the query holds no index list to narrow - name them with [index=...] to have the query authorized"
  }

}

sealed trait EsqlCommandKind

object EsqlCommandKind {

  case object SourceCommand extends EsqlCommandKind

  case object LookupJoin extends EsqlCommandKind

  implicit val show: Show[EsqlCommandKind] = Show.show {
    case SourceCommand => "source command index lists"
    case LookupJoin    => "LOOKUP JOIN targets"
  }

}
