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

import tech.beshu.ror.es.esql.Query.SourceLocation

/**
 * What ES's parser says about one index-reading node of the query: the list it read, and the raw text it read it
 * from, at the place that text sits.
 *
 * The written text is what makes the index list replaceable - the normalized list cannot be searched for, because
 * it is not what the user wrote.
 */
final case class ReportedIndexList(read: IndexListRead, writtenAt: SourceLocation, writtenText: String)
