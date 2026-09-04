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

import cats.data.NonEmptyList
import cats.syntax.traverse.*
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, IndexName, RequestedIndex}
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.es.esql.Query.SourceLocation
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*

final case class Query(value: String) extends AnyVal

object Query {

  final case class TextSpan(start: Int, end: Int)

  /** Where in a query's text something sits, the way ES reports it: a 1-based line and a 0-based column. */
  final case class SourceLocation(line: Int, column: Int)

}

/** Why ReadonlyREST cannot run an ES|QL query holding it to the indices the ACL allowed. */
sealed trait Rejection

object Rejection {

  case object CannotParseQuery extends Rejection

  final case class CannotExtractIndices(failure: ReadingFailure) extends Rejection

  final case class SubstitutionNotConfirmed(intendedIndexLists: List[String], readIndexLists: List[String])
      extends Rejection

}

/** Why an index list ES reported could not be found in the query text, or read as indices once found. */
sealed trait ReadingFailure

object ReadingFailure {

  final case class NotWhereEsReportedIt(reportedIndexList: String) extends ReadingFailure

  final case class SubqueryInSourceCommand(reportedIndexList: String) extends ReadingFailure

  case object PromqlLeaningOnDefaultIndex extends ReadingFailure

  case object IndexListInAnonymousParameter extends ReadingFailure

  final case class UnsupportedIndexList(reportedIndexList: String) extends ReadingFailure

}

/** An index list ES read out of a query, normalized its way (`FROM a, b` as `a,b`). All a rewrite is held to. */
sealed trait IndexListRead {

  def indexList: String

  /** ES reports an empty list for a source command of only subqueries. */
  def indexListIsEmpty: Boolean = indexList.isBlank

  def stringify: String = this match {
    case IndexListRead.SourceCommand(indexList) => indexList
    case IndexListRead.LookupJoin(indexList)    => s"LOOKUP JOIN ${indexList}"
  }

}

object IndexListRead {

  final case class SourceCommand(indexList: String) extends IndexListRead

  final case class LookupJoin(indexList: String) extends IndexListRead

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

  def requestedIndicesOf(indexLists: NonEmptyList[LocatedIndexList]): Set[RequestedIndex[ClusterIndexName]] =
    indexLists.toList.flatMap(_.requestedIndices.toList).toCovariantSet

  /** All of them or none: an entry ROR cannot read is an index it would leave the ACL unaware of. */
  private def requestedIndicesIn(read: IndexListRead): Option[NonEmptyList[RequestedIndex[ClusterIndexName]]] =
    read.indexList
      .split(',')
      .asSafeList
      .filter(_.nonEmpty)
      .traverse(RequestedIndex.fromString)
      .flatMap(NonEmptyList.fromList)

}

/** A rewritten query, together with what ES has to read out of it for the rewrite to have done its job. */
final case class ReplacedQuery(query: Query, intendedReads: List[IndexListRead]) {

  /** Held to what ES reads back out of the rewrite - the only thing saying which indices it will really run against. */
  def checkedAgainst(esReads: List[IndexListRead]): Either[Rejection, Query] = {
    val intended = intendedReads.map(_.stringify).sorted
    val read = esReads.map(_.stringify).sorted
    Either.cond(
      test = intended == read,
      right = query,
      left = Rejection.SubstitutionNotConfirmed(intended, read)
    )
  }

}

sealed trait RequestClassification

object RequestClassification {

  final case class IndicesRelated(indexLists: NonEmptyList[LocatedIndexList]) extends RequestClassification {
    lazy val requestedIndices: Set[RequestedIndex[ClusterIndexName]] =
      LocatedIndexList.requestedIndicesOf(indexLists)
  }

  case object NonIndicesRelated extends RequestClassification

}
