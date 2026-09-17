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

trait EsqlQueryIndicesReader {

  def indicesIn(query: String): Either[Throwable, QueryIndices]

}

object EsqlQueryIndicesReader {

  /**
   * Both lists are empty for a query that reads no index, e.g. `ROW` or `SHOW INFO`. Neither has a fixed size: a
   * source command with subqueries reads from each of them, and a query can have many `LOOKUP JOIN` commands.
   */
  final case class QueryIndices(fromSources: List[IndexPatternInQuery], lookupJoins: List[IndexPatternInQuery])

  final case class IndexPatternInQuery(indexPattern: String, writtenAt: SourceLocation, writtenText: String) {

    /** ES reports an empty pattern for a source command of only subqueries. */
    private[esql] def isEmpty: Boolean = indexPattern.isBlank

  }

  /** A 1-based line and a 0-based column, the way ES reports them. */
  final case class SourceLocation(line: Int, column: Int)

}
