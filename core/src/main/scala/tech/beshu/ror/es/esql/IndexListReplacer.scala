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
import tech.beshu.ror.es.esql.LocatedIndexList.{LookupJoinTarget, SourceCommandIndices}
import tech.beshu.ror.es.esql.Query.TextSpan
import tech.beshu.ror.syntax.*

object IndexListReplacer {

  def replacing(
      query: Query,
      indexLists: NonEmptyList[LocatedIndexList],
      allowedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
  ): ReplacedQuery = {
    val allowedIndexNames: Set[ClusterIndexName] = allowedIndices.includedOnly

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
        Edit(
          indexList.span,
          allowedIndicesFor(indexList, allowedIndexNames, reachableOnlyThroughLookupJoin, scope),
          isLookupJoin = false
        )
      } ::: lookupJoinTargets.map { indexList =>
        Edit(indexList.span, allowedIndicesFor(indexList, allowedIndexNames), isLookupJoin = true)
      }

    ReplacedQuery(rewritten(query, edits), edits.map(edit => IndexListRead(edit.isLookupJoin, edit.newIndexList)))
  }

  /**
   * A lone source command gets the whole ACL-resolved set, because a rule may resolve a request to indices the
   * written pattern never matched (`FROM bookshop` under `indices: ["bookstore"]`). That set cannot be attributed
   * to any single command once there are several, where handing it to each would answer a subquery the ACL denied
   * with another command's rows.
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

  private def rewritten(query: Query, edits: List[Edit]): Query = {
    Query(
      edits.sortBy(-_.span.start).foldLeft(query.value) { case (text, edit) =>
        s"${text.substring(0, edit.span.start)}${edit.newIndexList}${text.substring(edit.span.end)}"
      }
    )
  }

  private final case class Edit(span: TextSpan, newIndices: NonEmptyList[ClusterIndexName], isLookupJoin: Boolean) {
    def newIndexList: String = newIndices.toList.map(_.stringify).mkString(",")
  }

  private sealed trait SourceCommandScope

  private object SourceCommandScope {
    case object TheOnlyOne extends SourceCommandScope
    case object OneOfSeveral extends SourceCommandScope
  }

}
