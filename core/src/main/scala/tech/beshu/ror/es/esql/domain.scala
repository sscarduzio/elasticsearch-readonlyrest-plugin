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
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, IndexName, RequestedIndex}
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.es.esql.Query.SourceLocation
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*

final case class Query(value: String) extends AnyVal

object Query {

  /** Where in a query's text something sits, as a half-open range of characters. */
  final case class TextSpan(start: Int, end: Int)

  /** Where in a query's text something sits, the way ES reports it: a 1-based line and a 0-based column. */
  final case class SourceLocation(line: Int, column: Int)

  /** Why a query cannot be run at all, once ROR knows it cannot hold it to the indices the ACL allowed. */
  sealed trait Rejection

  object Rejection {

    case object CannotParse extends Rejection

    final case class CannotReadIndexList(failure: LocatedIndexList.ReadingFailure) extends Rejection

    final case class NotReplacedAsIntended(intendedIndexLists: List[String], readIndexLists: List[String])
        extends Rejection

    implicit val show: Show[Rejection] = Show.show {
      case CannotParse =>
        "The ES|QL query has been forbidden. ReadonlyREST has to rewrite such a query so that it reads only the " +
          "indices the user is allowed to, and it could not read the query at all - so it cannot tell which indices " +
          "the query would run against. If the query is valid ES|QL, please report it to the ReadonlyREST team."
      case CannotReadIndexList(failure) =>
        s"The ES|QL query has been forbidden. ReadonlyREST has to rewrite such a query so that it reads only the " +
          s"indices the user is allowed to, and running it as written would have let the user read the indices " +
          s"they asked for, unchecked. It could not be rewritten, because ${failure.show}."
      case NotReplacedAsIntended(intended, read) =>
        s"The ES|QL query has been forbidden. ReadonlyREST rewrote it to read only [${intended.mkString(", ")}], " +
          s"the indices the user is allowed to, but Elasticsearch reads the rewritten query as reading " +
          s"[${read.mkString(", ")}] instead. Since the two disagree, ReadonlyREST cannot tell which indices the " +
          s"query would really read, so it does not run it. Please report this query to the ReadonlyREST team."
    }

  }

}

/** An index list ES read out of a query, normalized its way (`FROM a, b` as `a,b`). All a rewrite is held to. */
sealed trait IndexListRead {

  def indexList: String

  /** ES reports an empty list for a source command of only subqueries. */
  def indexListIsEmpty: Boolean = indexList.isBlank
}

object IndexListRead {

  final case class SourceCommand(indexList: String) extends IndexListRead

  final case class LookupJoin(indexList: String) extends IndexListRead

  implicit val show: Show[IndexListRead] = Show.show {
    case SourceCommand(indexList) => indexList
    case LookupJoin(indexList)    => s"LOOKUP JOIN ${indexList}"
  }

}

/** An index list ES read, plus the query text it read it from and where that sits - the list alone is unsearchable. */
final case class ReportedIndexList(read: IndexListRead, writtenAt: SourceLocation, writtenText: String)

/** An index list found in the query text, so the indices it names can be replaced with the ones the ACL allowed. */
sealed trait LocatedIndexList {
  def span: Query.TextSpan
  def requestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
}

object LocatedIndexList {

  final case class SourceCommandIndices private (
      span: Query.TextSpan,
      requestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
  ) extends LocatedIndexList {

    lazy val writtenPattern: PatternsMatcher[ClusterIndexName] =
      PatternsMatcher.create(requestedIndices.includedOnly)
  }

  object SourceCommandIndices {
    def parse(span: Query.TextSpan, read: IndexListRead.SourceCommand): Option[SourceCommandIndices] =
      requestedIndicesIn(read).map(SourceCommandIndices(span, _))
  }

  final case class LookupJoinTarget private (span: Query.TextSpan, index: ClusterIndexName) extends LocatedIndexList {
    override def requestedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]] =
      NonEmptyList.one(RequestedIndex(index, excluded = false))
  }

  object LookupJoinTarget {

    /** A join reads one index, named in full: ES resolves neither a wildcard nor a remote cluster here. */
    def parse(span: Query.TextSpan, read: IndexListRead.LookupJoin): Option[LookupJoinTarget] =
      requestedIndicesIn(read).collect {
        case NonEmptyList(RequestedIndex(index @ ClusterIndexName.Local(_: IndexName.Full), false), Nil) =>
          LookupJoinTarget(span, index)
      }

  }

  /** Why an index list ES reported could not be found in the query text, or read as indices once found. */
  sealed trait ReadingFailure

  object ReadingFailure {

    final case class NotWhereEsReportedIt(reportedIndexList: String) extends ReadingFailure

    final case class SubqueryInSourceCommand(reportedIndexList: String) extends ReadingFailure

    case object PromqlLeaningOnDefaultIndex extends ReadingFailure

    final case class UnsupportedIndexList(reportedIndexList: String) extends ReadingFailure

    implicit val show: Show[ReadingFailure] = Show.show {
      case NotWhereEsReportedIt(indexList) =>
        s"Elasticsearch says the query reads [${indexList.show}], but points at a place in the query text where " +
          s"that is not what is written - so there is nothing ReadonlyREST can safely rewrite. Please report " +
          s"this query to the ReadonlyREST team"
      case SubqueryInSourceCommand(indexList) =>
        s"the indices [${indexList.show}] are read by a command that also holds a subquery, and Elasticsearch " +
          s"reports the two merged into a single list - so ReadonlyREST cannot tell which part of the query " +
          s"text to narrow down. Write the subquery as a separate command to have such a query authorized"
      case PromqlLeaningOnDefaultIndex =>
        "the PROMQL command names no [index] parameter, so it reads whichever indices Elasticsearch defaults to " +
          "and the query text holds no index list to narrow down. Add [index=...] to have such a query authorized"
      case UnsupportedIndexList(indexList) =>
        s"[${indexList.show}] is not something ReadonlyREST can read as a list of index names"
    }

  }

  def requestedIndicesOf(indexLists: NonEmptyList[LocatedIndexList]): Set[RequestedIndex[ClusterIndexName]] =
    indexLists.toList.flatMap(_.requestedIndices.toList).toCovariantSet

  private def requestedIndicesIn(read: IndexListRead): Option[NonEmptyList[RequestedIndex[ClusterIndexName]]] =
    NonEmptyList.fromList(
      read.indexList.split(',').asSafeList.filter(_.nonEmpty).flatMap(RequestedIndex.fromString)
    )

}

/** A rewritten query, together with what ES has to read out of it for the rewrite to have done its job. */
final case class ReplacedQuery(query: Query, intendedReads: List[IndexListRead]) {

  /** Held to what ES reads back out of the rewrite - the only thing saying which indices it will really run against. */
  def checkedAgainst(esReads: List[IndexListRead]): Either[Query.Rejection, Query] = {
    val intended = intendedReads.sortBy(_.show)
    val read = esReads.sortBy(_.show)
    Either.cond(
      test = intended == read,
      right = query,
      left = Query.Rejection.NotReplacedAsIntended(intended.map(_.show), read.map(_.show))
    )
  }

}

/** What ROR made of a query: the index lists it names, or nothing it has to hold to the ACL. */
sealed trait RequestClassification

object RequestClassification {

  final case class IndicesRelated(indexLists: NonEmptyList[LocatedIndexList]) extends RequestClassification {
    lazy val requestedIndices: Set[RequestedIndex[ClusterIndexName]] =
      LocatedIndexList.requestedIndicesOf(indexLists)
  }

  case object NonIndicesRelated extends RequestClassification

  /** Why ROR could not read a query into the index lists it names. */
  sealed trait Error

  object Error {

    /** ES rejects such a query on its own, so ROR can let it through rather than answer for a syntax error. */
    final case class NotParsable(cause: Throwable) extends Error

    final case class CannotReadIndexList(failure: LocatedIndexList.ReadingFailure) extends Error
  }

}
