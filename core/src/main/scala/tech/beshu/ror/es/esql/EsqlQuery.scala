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

final case class EsqlQuery(value: String) extends AnyVal

final case class QueryTextSpan(start: Int, end: Int)

/** ES reports a line as 1-based and a column as 0-based, the way ANTLR hands them to it. */
final case class EsqlSourceLocation(line: Int, column: Int)

/**
 * One index-reading node of the parsed query, as ES's own parser describes it: the index list ES read out of it,
 * normalized (`FROM a, b` becomes `a,b`), together with the place in the query text it was written at.
 *
 * The written text is what makes the query narrowable - the normalized list cannot be searched for, because it is
 * not what the user wrote.
 */
final case class EsqlReportedRelation(
    indexList: String,
    writtenAt: EsqlSourceLocation,
    writtenText: String,
    isLookupJoin: Boolean
)
