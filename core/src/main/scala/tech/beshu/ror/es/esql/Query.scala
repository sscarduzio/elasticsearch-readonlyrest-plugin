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

final case class Query(value: String) extends AnyVal

object Query {

  /** Where in a query's text something sits, as a half-open range of characters. */
  final case class TextSpan(start: Int, end: Int)

  /** Where in a query's text something sits, the way ES reports it: a 1-based line and a 0-based column. */
  final case class SourceLocation(line: Int, column: Int)
}
