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
import tech.beshu.ror.implicits.*

/** Why an index list ES reported could not be found in the query text, or read as indices once found. */
sealed trait IndexListReadingFailure

object IndexListReadingFailure {

  final case class NotWhereEsReportedIt(reportedIndexList: String) extends IndexListReadingFailure

  final case class SubqueryInSourceCommand(reportedIndexList: String) extends IndexListReadingFailure

  case object PromqlLeaningOnDefaultIndex extends IndexListReadingFailure

  final case class UnsupportedIndexList(reportedIndexList: String) extends IndexListReadingFailure

  implicit val show: Show[IndexListReadingFailure] = Show.show {
    case NotWhereEsReportedIt(indexList) =>
      s"the index list [${indexList.show}] is not written where ES reported it to be"
    case SubqueryInSourceCommand(indexList) =>
      s"the source command reading [${indexList.show}] holds a subquery, which ES reports merged into the " +
        "surrounding index list, leaving no index list of its own to replace"
    case PromqlLeaningOnDefaultIndex =>
      "the PROMQL command names no [index] parameter, so the indices it reads are the ones ES defaults to and " +
        "the query holds no index list to replace - name them with [index=...] to have the query authorized"
    case UnsupportedIndexList(indexList) =>
      s"the index list [${indexList.show}] cannot be read as indices"
  }

}
