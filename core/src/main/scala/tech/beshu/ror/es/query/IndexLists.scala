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
package tech.beshu.ror.es.query

import cats.data.NonEmptyList
import cats.syntax.traverse.*
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestedIndex}
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*

private[es] object IndexLists {

  /** All of them or none: an entry ROR cannot read is an index it would leave the ACL unaware of. */
  def requestedIndicesIn(indexList: String): Option[NonEmptyList[RequestedIndex[ClusterIndexName]]] =
    indexList
      .split(',')
      .asSafeList
      .map(_.trim)
      .filter(_.nonEmpty)
      .traverse(RequestedIndex.fromString)
      .flatMap(NonEmptyList.fromList)

  /**
   * A rewritten index list cannot express an exclusion, so one the ACL left in has to be applied here - and an
   * allowed pattern an exclusion falls under has to go whole, since keeping it would read that exclusion back in.
   */
  def allowedIndexNamesOf(
      filteredRequestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
  ): Set[ClusterIndexName] = {
    val included = filteredRequestedIndices.includedOnly
    filteredRequestedIndices.toList.filter(_.excluded).map(_.name) match {
      case Nil      => included
      case excluded => included.filterNot(name => excluded.exists(overlapping(name, _)))
    }
  }

  private def overlapping(one: ClusterIndexName, other: ClusterIndexName): Boolean =
    PatternsMatcher.create(Set(one)).`match`(other) || PatternsMatcher.create(Set(other)).`match`(one)

}
