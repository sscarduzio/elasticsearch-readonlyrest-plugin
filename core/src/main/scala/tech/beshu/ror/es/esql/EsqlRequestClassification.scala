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
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestedIndex}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*

sealed trait EsqlRequestClassification

object EsqlRequestClassification {

  final case class IndicesRelated(tables: NonEmptyList[EsqlIndexTable]) extends EsqlRequestClassification {
    lazy val requestedIndices: Set[RequestedIndex[ClusterIndexName]] = EsqlIndexTable.requestedIndicesOf(tables)
  }

  case object NonIndicesRelated extends EsqlRequestClassification
}

sealed trait EsqlClassificationError

object EsqlClassificationError {

  final case class NotParsable(cause: Throwable) extends EsqlClassificationError

  final case class CannotReadIndexList(failure: EsqlIndexListReadingFailure) extends EsqlClassificationError
}

sealed trait EsqlIndexListReadingFailure

object EsqlIndexListReadingFailure {

  final case class NotWhereEsReportedIt(reportedIndexList: String) extends EsqlIndexListReadingFailure

  final case class DoesNotMatchEsReport(writtenIndexList: String, reportedIndexList: String)
      extends EsqlIndexListReadingFailure

  final case class SubqueryInSourceCommand(reportedIndexList: String) extends EsqlIndexListReadingFailure

  case object PromqlLeaningOnDefaultIndex extends EsqlIndexListReadingFailure

  final case class UnsupportedIndexList(reportedIndexList: String) extends EsqlIndexListReadingFailure

  implicit val show: Show[EsqlIndexListReadingFailure] = Show.show {
    case NotWhereEsReportedIt(indexList) =>
      s"the index list [${indexList.show}] is not written where ES reported it to be"
    case DoesNotMatchEsReport(written, reported) =>
      s"the index list found in the query [${written.show}] is not the one ES reported [${reported.show}]"
    case SubqueryInSourceCommand(indexList) =>
      s"the source command reading [${indexList.show}] holds a subquery, which ES reports merged into the " +
        "surrounding index list, leaving no index list of its own to narrow"
    case PromqlLeaningOnDefaultIndex =>
      "the PROMQL command names no [index] parameter, so the indices it reads are the ones ES defaults to and " +
        "the query holds no index list to narrow - name them with [index=...] to have the query authorized"
    case UnsupportedIndexList(indexList) =>
      s"the index list [${indexList.show}] cannot be read as indices"
  }

}

sealed trait EsqlQueryRejection

object EsqlQueryRejection {

  final case class CannotReadIndexList(failure: EsqlIndexListReadingFailure) extends EsqlQueryRejection

  implicit val show: Show[EsqlQueryRejection] = Show.show { case CannotReadIndexList(failure) =>
    s"Cannot narrow the ES|QL query down to the indices the ACL allowed - the request is rejected, because " +
      s"passing it through would run it against the originally requested indices; ${failure.show}"
  }

}
