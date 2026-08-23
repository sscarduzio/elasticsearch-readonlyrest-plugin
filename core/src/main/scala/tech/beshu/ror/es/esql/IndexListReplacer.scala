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
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.es.esql.LocatedIndexList.{LookupJoinTarget, SourceCommandIndices}
import tech.beshu.ror.es.esql.Query.TextSpan
import tech.beshu.ror.syntax.*

object IndexListReplacer {

  def replacing(
      query: Query,
      indexLists: NonEmptyList[LocatedIndexList],
      allowedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
  ): ReplacedQuery = {
    val allowedIndexNames: Set[ClusterIndexName] = allowedIndexNamesOf(allowedIndices)

    val (lookupJoinTargets, sourceCommandIndices) = indexLists.toList.partitionMap {
      case indexList: LookupJoinTarget     => Left(indexList)
      case indexList: SourceCommandIndices => Right(indexList)
    }

    val reachableOnlyThroughLookupJoin: Set[ClusterIndexName] =
      lookupJoinTargets.map(_.index).toCovariantSet -- sourceCommandIndices
        .flatMap(_.requestedIndices.includedOnly)
        .toCovariantSet

    val scope =
      if (sourceCommandIndices.sizeIs == 1) SourceCommandScope.TheOnlyOne else SourceCommandScope.OneOfSeveral

    val edits =
      sourceCommandIndices.map { indexList =>
        val allowed = allowedIndicesFor(indexList, allowedIndexNames, reachableOnlyThroughLookupJoin, scope)
        Edit(indexList.span, IndexListRead.BySourceCommand(indexListOf(allowed)))
      } ::: lookupJoinTargets.map { indexList =>
        Edit(indexList.span, IndexListRead.ByLookupJoin(indexListOf(allowedIndicesFor(indexList, allowedIndexNames))))
      }

    ReplacedQuery(rewritten(query, edits), edits.map(_.intendedRead))
  }

  /**
   * A rewritten index list cannot express an exclusion, so one the ACL left in has to be applied here - and an
   * allowed pattern an exclusion falls under has to go whole, since keeping it would read that exclusion back in.
   */
  private def allowedIndexNamesOf(
      allowedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
  ): Set[ClusterIndexName] = {
    val included = allowedIndices.includedOnly
    allowedIndices.toList.filter(_.excluded).map(_.name) match {
      case Nil      => included
      case excluded => included.filterNot(name => excluded.exists(overlapping(name, _)))
    }
  }

  private def overlapping(one: ClusterIndexName, other: ClusterIndexName): Boolean =
    PatternsMatcher.create(Set(one)).`match`(other) || PatternsMatcher.create(Set(other)).`match`(one)

  /**
   * A lone source command gets the whole ACL-resolved set, since a rule may resolve to indices the written pattern
   * never matched (`FROM bookshop` under `indices: ["bookstore"]`). With several, that set belongs to no single one
   * of them, so each keeps only what its own pattern matched.
   */
  private def allowedIndicesFor(
      indexList: SourceCommandIndices,
      allowedIndices: Set[ClusterIndexName],
      reachableOnlyThroughLookupJoin: Set[ClusterIndexName],
      scope: SourceCommandScope
  ): NonEmptyList[ClusterIndexName] = {
    val namesMatchingWrittenPattern = indexList.writtenPattern.filter(allowedIndices)
    val names = scope match {
      case SourceCommandScope.TheOnlyOne =>
        (allowedIndices -- reachableOnlyThroughLookupJoin) ++ namesMatchingWrittenPattern
      case SourceCommandScope.OneOfSeveral =>
        namesMatchingWrittenPattern
    }
    NonEmptyList.fromList(names.toList).getOrElse(maskedAsNonexistent)
  }

  private def allowedIndicesFor(
      indexList: LookupJoinTarget,
      allowedIndices: Set[ClusterIndexName]
  ): NonEmptyList[ClusterIndexName] =
    if (allowedIndices.contains(indexList.index)) NonEmptyList.one(indexList.index) else maskedAsNonexistent

  private def maskedAsNonexistent: NonEmptyList[ClusterIndexName] =
    NonEmptyList.one(ClusterIndexName.Local.randomNonexistentIndex())

  private def indexListOf(indices: NonEmptyList[ClusterIndexName]): String =
    indices.toList.map(_.stringify).mkString(",")

  private def rewritten(query: Query, edits: List[Edit]): Query = {
    Query(
      edits.sortBy(-_.span.start).foldLeft(query.value) { case (text, edit) =>
        s"${text.substring(0, edit.span.start)}${edit.intendedRead.indexList}${text.substring(edit.span.end)}"
      }
    )
  }

  private final case class Edit(span: TextSpan, intendedRead: IndexListRead)

  private sealed trait SourceCommandScope

  private object SourceCommandScope {
    case object TheOnlyOne extends SourceCommandScope
    case object OneOfSeveral extends SourceCommandScope
  }

}
