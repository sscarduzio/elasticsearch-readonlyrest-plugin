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

  final case class UnsupportedIndexList(text: NormalizedIndexList) extends EsqlClassificationError

  final case class UnreviewedQueryContent(preAnalysisFields: NonEmptyList[String]) extends EsqlClassificationError
}

sealed trait EsqlQueryRejection

object EsqlQueryRejection {

  final case class UnsupportedIndexList(text: NormalizedIndexList) extends EsqlQueryRejection

  final case class CannotNarrowQuery(cause: EsqlNarrowingFailure) extends EsqlQueryRejection

  final case class UnreviewedQueryContent(preAnalysisFields: NonEmptyList[String]) extends EsqlQueryRejection

  implicit val show: Show[EsqlQueryRejection] = Show.show {
    case UnsupportedIndexList(text) =>
      s"Cannot read the index list [${text.show}] of the ES|QL query as indices - the request is rejected, because " +
        "passing it through would run it against indices the ACL was never given a chance to check"
    case UnreviewedQueryContent(fields) =>
      s"The ES|QL query is read by ES into [${fields.toList.mkString(", ")}] of its pre-analysis, which this ROR " +
        "version does not read - the request is rejected, because passing it through would run it against " +
        "indices the ACL was never given a chance to check"
    case CannotNarrowQuery(cause) =>
      "Cannot narrow the ES|QL query down to the indices the ACL allowed - the request is rejected, because " +
        s"passing it through would run it against the originally requested indices; ${cause.show}"
  }

}
