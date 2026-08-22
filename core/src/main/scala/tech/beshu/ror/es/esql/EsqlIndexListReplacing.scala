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
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestedIndex}
import tech.beshu.ror.es.esql.EsqlIndexTable.{LookupJoin, SourceCommand}
import tech.beshu.ror.syntax.*

/** A rewritten query, together with what ES has to read out of it for the rewrite to have done its job. */
final case class EsqlReplacedQuery(query: EsqlQuery, intendedReads: List[EsqlIndexListRead])

object EsqlIndexListReplacing {

  def queryWithAllowedIndices(
      query: EsqlQuery,
      tables: NonEmptyList[EsqlIndexTable],
      allowedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
  ): EsqlReplacedQuery = {
    val allowedIndexNames: Set[ClusterIndexName] = allowedIndices.includedOnly

    val (lookupJoins, sourceCommands) = tables.toList.partitionMap {
      case table: LookupJoin    => Left(table)
      case table: SourceCommand => Right(table)
    }

    val reachableOnlyThroughLookupJoin: Set[ClusterIndexName] =
      lookupJoins.map(_.index).toCovariantSet -- sourceCommands.flatMap(_.requestedIndices.includedOnly).toCovariantSet

    val scope =
      if (sourceCommands.sizeIs == 1) SourceCommandScope.TheOnlyOne else SourceCommandScope.OneOfSeveral

    val edits =
      sourceCommands.map { table =>
        Edit(
          table.indexListSpan,
          allowedIndicesFor(table, allowedIndexNames, reachableOnlyThroughLookupJoin, scope),
          isLookupJoin = false
        )
      } ::: lookupJoins.map { table =>
        Edit(table.indexListSpan, allowedIndicesFor(table, allowedIndexNames), isLookupJoin = true)
      }

    EsqlReplacedQuery(
      rewritten(query, edits),
      edits.map(edit => EsqlIndexListRead(edit.isLookupJoin, edit.newIndexList))
    )
  }

  /**
   * The rewrite is held to what ES reads back out of it, rather than to what ROR read out of the query it was given.
   * Only the second says the index lists ES will run the query against are the ones the ACL allowed - a span read a
   * character short, or a name that needed quoting, shows up here and nowhere else.
   */
  def verified(
      replaced: EsqlReplacedQuery,
      esReadsFromRewrittenQuery: List[EsqlIndexListRead]
  ): Either[EsqlQueryRejection, EsqlQuery] = {
    val intended = rendered(replaced.intendedReads)
    val read = rendered(esReadsFromRewrittenQuery)
    Either.cond(
      test = intended == read,
      right = replaced.query,
      left = EsqlQueryRejection.QueryNotReplacedAsIntended(intended, read)
    )
  }

  private def rendered(reads: List[EsqlIndexListRead]): List[String] = {
    import tech.beshu.ror.implicits.*
    reads.map(_.show).sorted
  }

  /**
   * A lone source command gets the whole ACL-resolved set, because a rule may resolve a request to indices the
   * written pattern never matched (`FROM bookshop` under `indices: ["bookstore"]`). That set cannot be attributed
   * to any single command once there are several, where handing it to each would answer a subquery the ACL denied
   * with another command's rows.
   */
  private def allowedIndicesFor(
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

  private def allowedIndicesFor(
      table: LookupJoin,
      allowedIndices: Set[ClusterIndexName]
  ): NonEmptyList[ClusterIndexName] =
    if (allowedIndices.contains(table.index)) NonEmptyList.one(table.index) else maskedAsNonexistent

  private def maskedAsNonexistent: NonEmptyList[ClusterIndexName] =
    NonEmptyList.one(ClusterIndexName.Local.randomNonexistentIndex())

  private def rewritten(query: EsqlQuery, edits: List[Edit]): EsqlQuery = {
    EsqlQuery(
      edits.sortBy(-_.indexListSpan.start).foldLeft(query.value) { case (text, edit) =>
        s"${text.substring(0, edit.indexListSpan.start)}${edit.newIndexList}${text.substring(edit.indexListSpan.end)}"
      }
    )
  }

  private final case class Edit(
      indexListSpan: QueryTextSpan,
      newIndices: NonEmptyList[ClusterIndexName],
      isLookupJoin: Boolean
  ) {
    def newIndexList: String = newIndices.toList.map(_.stringify).mkString(",")
  }

  private sealed trait SourceCommandScope

  private object SourceCommandScope {
    case object TheOnlyOne extends SourceCommandScope
    case object OneOfSeveral extends SourceCommandScope
  }

}
