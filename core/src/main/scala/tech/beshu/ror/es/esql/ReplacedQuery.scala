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

import tech.beshu.ror.implicits.*

/** A rewritten query, together with what ES has to read out of it for the rewrite to have done its job. */
final case class ReplacedQuery(query: Query, intendedReads: List[IndexListRead]) {

  /**
   * The rewrite is held to what ES reads back out of it, rather than to what ROR read out of the query it was
   * given. Only the second says the index lists ES will run the query against are the ones the ACL allowed - a
   * span read a character short, or a name that needed quoting, shows up here and nowhere else.
   */
  def checkedAgainst(esReads: List[IndexListRead]): Either[QueryRejection, Query] = {
    val intended = rendered(intendedReads)
    val read = rendered(esReads)
    Either.cond(
      test = intended == read,
      right = query,
      left = QueryRejection.QueryNotReplacedAsIntended(intended, read)
    )
  }

  private def rendered(reads: List[IndexListRead]): List[String] = reads.map(_.show).sorted
}
