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
import tech.beshu.ror.es.query.IndexLists.allowedIndexNamesOf
import tech.beshu.ror.es.query.{QueryText, TextSpan}
import tech.beshu.ror.syntax.*

private[esql] object IndexListReplacer {

  def replacing(
      query: String,
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

    val masked = masksForForbiddenIndexLists(indexLists)

    val edits =
      sourceCommandIndices.map { indexList =>
        val allowed = allowedIndicesFor(indexList, allowedIndexNames, reachableOnlyThroughLookupJoin, scope)
        val indices = indexListOf(allowed.getOrElse(masked(indexList)))
        Edit(indexList.span, intendedIndexList = indices, textOf(indexList.writtenAs, indices))
      } ::: lookupJoinTargets.map { indexList =>
        val allowed = allowedIndicesFor(indexList, allowedIndexNames)
        val index = indexListOf(allowed.getOrElse(masked(indexList)))
        Edit(indexList.span, intendedIndexList = s"LOOKUP JOIN ${index}", index)
      }

    ReplacedQuery(
      QueryText.rewritten(query, edits.map(edit => (edit.span, edit.text))),
      edits.map(_.intendedIndexList).sorted
    )
  }

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
  ): Option[NonEmptyList[ClusterIndexName]] = {
    val namesMatchingWrittenPattern = indexList.writtenPattern.filter(allowedIndices)
    val names = scope match {
      case SourceCommandScope.TheOnlyOne =>
        (allowedIndices -- reachableOnlyThroughLookupJoin) ++ namesMatchingWrittenPattern
      case SourceCommandScope.OneOfSeveral =>
        namesMatchingWrittenPattern
    }
    NonEmptyList.fromList(names.toList)
  }

  private def allowedIndicesFor(
      indexList: LookupJoinTarget,
      allowedIndices: Set[ClusterIndexName]
  ): Option[NonEmptyList[ClusterIndexName]] =
    Option.when(allowedIndices.contains(indexList.index))(NonEmptyList.one(indexList.index))

  /**
   * One nonexistent index per index list left with nothing, so a list the ACL forbade in more than one place -
   * a source command and a join reading the same index - is masked as the same index in each of them.
   */
  private def masksForForbiddenIndexLists(
      indexLists: NonEmptyList[LocatedIndexList]
  ): LocatedIndexList => NonEmptyList[ClusterIndexName] = {
    val masks = indexLists.toList
      .map(_.requestedIndices)
      .distinct
      .map(requestedIndices =>
        (requestedIndices, NonEmptyList.one[ClusterIndexName](ClusterIndexName.Local.randomNonexistentIndex()))
      )
      .toMap
    indexList => masks(indexList.requestedIndices)
  }

  private def indexListOf(indices: NonEmptyList[ClusterIndexName]): String =
    indices.toList.map(_.stringify).mkString(",")

  private def textOf(syntax: IndexListSyntax, indexList: String): String = syntax match {
    case IndexListSyntax.BareIndexList        => indexList
    case IndexListSyntax.PromqlIndexParameter => s" index=$indexList"
  }

  private final case class Edit(span: TextSpan, intendedIndexList: String, text: String)

  final case class ReplacedQuery(query: String, intendedIndexLists: List[String]) {

    /** The replacer only edits text. This check makes sure that ES reads exactly the intended indices. */
    def checkedAgainst(readIndexLists: List[LocatedIndexList]): Either[Rejection, String] = {
      val read = readIndexLists.map(_.describe).sorted
      Either.cond(
        test = intendedIndexLists == read,
        right = query,
        left = Rejection.SubstitutionNotConfirmed(intendedIndexLists, read)
      )
    }

  }

  private sealed trait SourceCommandScope

  private object SourceCommandScope {
    case object TheOnlyOne extends SourceCommandScope
    case object OneOfSeveral extends SourceCommandScope
  }

}
