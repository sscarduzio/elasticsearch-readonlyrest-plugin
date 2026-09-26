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
package tech.beshu.ror.es.query.sql

import cats.data.NonEmptyList
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestedIndex}
import tech.beshu.ror.es.query.sql.SqlQuery.Rejection
import tech.beshu.ror.es.query.{IndexLists, QueryText}
import tech.beshu.ror.syntax.*

private[sql] object IndexListReplacer {

  def replacing(
      query: String,
      indexLists: NonEmptyList[LocatedIndexList],
      filteredRequestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
  ): ReplacedQuery = {
    val names = allowedIndexNamesOf(filteredRequestedIndices)
    val indexList = names.toList.map(_.stringify).sorted.mkString(",")
    val edits = indexLists.toList.map(located => (located.span, textOf(located.writtenAs, indexList)))
    ReplacedQuery(QueryText.rewritten(query, edits), names.map(_.stringify))
  }

  private def allowedIndexNamesOf(
      filteredRequestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
  ): Set[ClusterIndexName] = {
    val names = IndexLists.allowedIndexNamesOf(filteredRequestedIndices)
    if (names.nonEmpty) names else Set(ClusterIndexName.Local.randomNonexistentIndex())
  }

  private def textOf(syntax: IndexListSyntax, indexList: String): String = syntax match {
    case IndexListSyntax.InQueryText     => s""""$indexList""""
    case IndexListSyntax.AppendedToQuery => s""" "$indexList""""
  }

  final case class ReplacedQuery(query: String, intendedIndices: Set[String]) {

    /** The replacer only edits text. This check makes sure that ES reads exactly the intended indices. */
    def checkedAgainst(readIndexLists: List[LocatedIndexList]): Either[Rejection, String] = {
      val readIndices = readIndexLists.flatMap(_.indexNames).toCovariantSet
      Either.cond(
        test = intendedIndices == readIndices,
        right = query,
        left = Rejection.SubstitutionNotConfirmed(intendedIndices.toList.sorted, readIndices.toList.sorted)
      )
    }

  }

}
