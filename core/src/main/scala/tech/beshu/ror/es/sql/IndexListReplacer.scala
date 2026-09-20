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
package tech.beshu.ror.es.sql

import cats.data.NonEmptyList
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestedIndex}
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.syntax.*

private[sql] object IndexListReplacer {

  def replacing(
      query: String,
      indexLists: NonEmptyList[LocatedIndexList],
      allowedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
  ): ReplacedQuery = {
    val names = allowedIndexNamesOf(allowedIndices)
    val indexList = names.toList.map(_.stringify).sorted.mkString(",")
    val edits = indexLists.toList.map(located => (located.span, textOf(located.writtenAs, indexList)))
    ReplacedQuery(rewritten(query, edits), names.map(_.stringify))
  }

  /**
   * An index list cannot express an exclusion, so an allowed pattern an exclusion falls under has to go whole -
   * keeping it would read that exclusion back in.
   */
  private def allowedIndexNamesOf(
      allowedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
  ): Set[ClusterIndexName] = {
    val included = allowedIndices.includedOnly
    val names = allowedIndices.toList.filter(_.excluded).map(_.name) match {
      case Nil      => included
      case excluded => included.filterNot(name => excluded.exists(overlapping(name, _)))
    }
    if (names.nonEmpty) names else Set(ClusterIndexName.Local.randomNonexistentIndex())
  }

  private def overlapping(one: ClusterIndexName, other: ClusterIndexName): Boolean =
    PatternsMatcher.create(Set(one)).`match`(other) || PatternsMatcher.create(Set(other)).`match`(one)

  private def textOf(syntax: IndexListSyntax, indexList: String): String = syntax match {
    case IndexListSyntax.InQueryText     => s""""$indexList""""
    case IndexListSyntax.AppendedToQuery => s""" "$indexList""""
  }

  private def rewritten(query: String, edits: List[(TextSpan, String)]): String = {
    edits.sortBy { case (span, _) => -span.start }.foldLeft(query) { case (text, (span, replacement)) =>
      s"${text.substring(0, span.start)}$replacement${text.substring(span.end)}"
    }
  }

}
