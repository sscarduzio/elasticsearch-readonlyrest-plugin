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

import tech.beshu.ror.es.esql.EsqlQueryIndicesReader.QueryIndices
import tech.beshu.ror.es.query.IndexPatternInQuery

trait EsqlQueryIndicesReader {

  private[esql] final def indicesIn(query: String): Either[ReadError, List[LocatedIndexList]] =
    for {
      indices <- queryIndicesFrom(query).left.map(ReadError.QueryNotParsed.apply)
      indexLists <- IndexListLocator.locatedIn(query, indices).left.map(ReadError.IndicesNotLocated.apply)
    } yield indexLists

  protected def queryIndicesFrom(query: String): Either[Throwable, QueryIndices]

}

object EsqlQueryIndicesReader {

  /**
   * Both lists are empty for a query that reads no index, e.g. `ROW` or `SHOW INFO`. Neither has a fixed size: a
   * source command with subqueries reads from each of them, and a query can have many `LOOKUP JOIN` commands.
   */
  final case class QueryIndices(fromSources: List[IndexPatternInQuery], lookupJoins: List[IndexPatternInQuery]) {

    /** ES reports a relation once for each `FORK` branch built on it, each time at the same place in the query. */
    private[esql] def withoutRepeats: QueryIndices = QueryIndices(fromSources.distinct, lookupJoins.distinct)

  }

}
