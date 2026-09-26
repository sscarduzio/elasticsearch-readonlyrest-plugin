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

import scala.util.Try

private[es] final case class TextSpan(start: Int, end: Int)

/** A 1-based line and a 0-based column, the way ES reports them. */
final case class SourceLocation(line: Int, column: Int)

final case class IndexPatternInQuery(reportedIndexList: String, writtenAt: SourceLocation, writtenText: String)

/** The unit a parser counts the column of a source location in. */
private[es] sealed trait ColumnUnit {

  def offsetIn(query: String, lineStart: Int, column: Int): Option[Int]

}

private[es] object ColumnUnit {

  case object CodePoints extends ColumnUnit {
    override def offsetIn(query: String, lineStart: Int, column: Int): Option[Int] =
      Try(query.offsetByCodePoints(lineStart, column)).toOption
  }

  case object Utf16Units extends ColumnUnit {
    override def offsetIn(query: String, lineStart: Int, column: Int): Option[Int] =
      Some(lineStart + column).filter(_ <= query.length)
  }

}
