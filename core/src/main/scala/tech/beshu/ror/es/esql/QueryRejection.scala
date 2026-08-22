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
import tech.beshu.ror.implicits.*

/** Why a query cannot be run at all, once ROR knows it cannot hold it to the indices the ACL allowed. */
sealed trait QueryRejection

object QueryRejection {

  final case class CannotReadIndexList(failure: IndexListReadingFailure) extends QueryRejection

  final case class UnreviewedQueryContent(planLeafTypes: NonEmptyList[String]) extends QueryRejection

  /** Raised after the rewrite, by holding it to what ES reads back out of the query ROR produced. */
  final case class QueryNotReplacedAsIntended(intendedIndexLists: List[String], readIndexLists: List[String])
      extends QueryRejection

  implicit val show: Show[QueryRejection] = Show.show {
    case CannotReadIndexList(failure) =>
      s"Cannot replace the ES|QL query's index lists with the ones the ACL allowed - the request is rejected, " +
        s"because passing it through would run it against the originally requested indices; ${failure.show}"
    case QueryNotReplacedAsIntended(intended, read) =>
      s"The ES|QL query ROR rewrote is read by ES as running against [${read.mkString(", ")}] rather than the " +
        s"[${intended.mkString(", ")}] the ACL allowed - the request is rejected, because ROR cannot tell what " +
        "the rewritten query would actually read"
    case UnreviewedQueryContent(leafTypes) =>
      s"The ES|QL query is read by ES into the plan leaves [${leafTypes.toList.mkString(", ")}], whose indices " +
        "this ROR version does not read - the request is rejected, because passing it through would run it " +
        "against indices the ACL was never given a chance to check"
  }

}
