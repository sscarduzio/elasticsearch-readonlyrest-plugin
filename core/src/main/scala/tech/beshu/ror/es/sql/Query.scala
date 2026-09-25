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
package tech.beshu.ror.es.sql

import cats.data.NonEmptyList
import cats.syntax.traverse.*
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestId, RequestedIndex}
import tech.beshu.ror.es.sql.CommandSelector.{
  AppendableIndexList,
  CannotNarrow,
  LiteralIndexList,
  MatchingPattern,
  NotIndexRelated
}
import tech.beshu.ror.es.sql.SqlPlanReader.{PlanFailure, SqlPlan}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.RequestIdAwareLogging
import tech.beshu.ror.utils.ScalaOps.*

sealed trait Query {

  protected def text: String

  def stringify: String = text

  def indices: Set[RequestedIndex[ClusterIndexName]]

  def narrowedTo(
      allowedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
  )(
      implicit requestId: RequestId
  ): Either[Rejection, Query]

}

object Query extends RequestIdAwareLogging {

  def from(query: String, reader: SqlPlanReader)(
      implicit requestId: RequestId
  ): Query = {
    if (Option(query).forall(_.isBlank)) WithoutIndices(query)
    else
      reader.planIn(query) match {
        case Right(plan) =>
          located(query, reader, plan)
        case Left(PlanFailure.RejectedByEs(cause)) =>
          logger.debug("Elasticsearch cannot parse the SQL query", cause)
          Unreadable(query, Rejection.CannotParseQuery)
        case Left(PlanFailure.CannotReadPlan(cause)) =>
          logger.warn("ReadonlyREST cannot read the plan Elasticsearch built for the SQL query", cause)
          Unreadable(query, Rejection.CannotReadQuery)
      }
  }

  final class WithIndexLists private[sql] (
      protected val text: String,
      private val reader: SqlPlanReader,
      private[sql] val indexLists: NonEmptyList[LocatedIndexList]
  ) extends Query {

    override lazy val indices: Set[RequestedIndex[ClusterIndexName]] =
      indexLists.toList.flatMap(_.requestedIndices.toList).toCovariantSet

    override def narrowedTo(
        allowedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
    )(
        implicit requestId: RequestId
    ): Either[Rejection, Query] = {
      if (allowedIndices.toList.toCovariantSet == indices) Right(this)
      else {
        val replaced = IndexListReplacer.replacing(text, indexLists, allowedIndices)
        reader.planIn(replaced.query) match {
          case Right(plan) =>
            replaced
              .checkedAgainst(indicesReadIn(plan))
              .flatMap(narrowed => confirmed(located(narrowed, reader, plan)))
          case Left(PlanFailure.RejectedByEs(cause)) =>
            logger.warn("Elasticsearch cannot parse the SQL query ReadonlyREST rewrote", cause)
            Left(Rejection.CannotParseRewrittenQuery(replaced.intendedIndices.toList.sorted))
          case Left(PlanFailure.CannotReadPlan(cause)) =>
            logger.warn("ReadonlyREST cannot read the plan Elasticsearch built for the rewritten SQL query", cause)
            Left(Rejection.CannotReadQuery)
        }
      }
    }

    private def confirmed(narrowed: Query): Either[Rejection, Query] = narrowed match {
      case Unreadable(_, reason) => Left(reason)
      case query                 => Right(query)
    }

  }

  final case class WithoutIndices(text: String) extends Query {

    override def indices: Set[RequestedIndex[ClusterIndexName]] = allIndices

    override def narrowedTo(
        allowedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
    )(
        implicit requestId: RequestId
    ): Either[Rejection, Query] = Right(this)

  }

  final case class Unreadable(text: String, reason: Rejection) extends Query {

    override def indices: Set[RequestedIndex[ClusterIndexName]] = allIndices

    override def narrowedTo(
        allowedIndices: NonEmptyList[RequestedIndex[ClusterIndexName]]
    )(
        implicit requestId: RequestId
    ): Either[Rejection, Query] = {
      Either.cond[Rejection, Query](allowedIndices.toList.toCovariantSet == indices, this, reason)
    }

  }

  private def allIndices: Set[RequestedIndex[ClusterIndexName]] =
    Set(RequestedIndex(ClusterIndexName.Local.wildcard, excluded = false))

  private def located(query: String, reader: SqlPlanReader, plan: SqlPlan): Query = {
    indexListsIn(query, plan) match {
      case Left(failure) =>
        Unreadable(query, Rejection.CannotLocateIndexList(failure))
      case Right(indexLists) =>
        NonEmptyList.fromList(indexLists) match {
          case Some(nonEmptyIndexLists) => new WithIndexLists(query, reader, nonEmptyIndexLists)
          case None                     => WithoutIndices(query)
        }
    }
  }

  private def indexListsIn(query: String, plan: SqlPlan): Either[ReadingFailure, List[LocatedIndexList]] =
    plan match {
      case SqlPlan.Statement(Nil) =>
        Right(Nil)
      case SqlPlan.Statement(tableIdentifiers) =>
        for {
          tables <- tableIdentifiers.traverse(tableInQuery)
          indexLists <- tables.distinct.traverse(IndexListLocator.locatedTable(query, _))
          _ <- checkNoneOverlaps(query, indexLists)
        } yield indexLists
      case SqlPlan.Command(command) =>
        IndexListLocator.locatedSelector(query, EsSqlObjects.selectorOf(command))
    }

  private def tableInQuery(tableIdentifier: Any): Either[ReadingFailure, TableInQuery] =
    EsSqlObjects.tableInQuery(tableIdentifier).toRight(ReadingFailure.CannotReadTable)

  private def checkNoneOverlaps(
      query: String,
      indexLists: List[LocatedIndexList]
  ): Either[ReadingFailure, Unit] = {
    indexLists
      .map(_.span)
      .sortBy(span => (span.start, span.end))
      .sliding(2)
      .collectFirst {
        case List(one, next) if next.start < one.end || next.start == one.start =>
          ReadingFailure.OverlappingIndexLists(
            query.substring(one.start, one.end),
            query.substring(next.start, next.end)
          )
      }
      .toLeft(())
  }

  private def indicesReadIn(plan: SqlPlan): Set[String] = plan match {
    case SqlPlan.Statement(tableIdentifiers) =>
      tableIdentifiers
        .flatMap(EsSqlObjects.tableInQuery)
        .flatMap(table => names(table.reportedIndexList))
        .toCovariantSet
    case SqlPlan.Command(command) =>
      EsSqlObjects.selectorOf(command) match {
        case NotIndexRelated | AppendableIndexList | CannotNarrow(_) => Set.empty
        case LiteralIndexList(indexList)                             => names(indexList)
        case MatchingPattern(wildcard, _)                            => Set(wildcard)
      }
  }

  private def names(indexList: String): Set[String] =
    indexList.split(',').asSafeList.map(_.trim).filter(_.nonEmpty).toCovariantSet

}

sealed trait Rejection

object Rejection {

  case object CannotReadQuery extends Rejection

  case object CannotParseQuery extends Rejection

  final case class CannotLocateIndexList(failure: ReadingFailure) extends Rejection

  final case class CannotParseRewrittenQuery(intendedIndices: List[String]) extends Rejection

  final case class SubstitutionNotConfirmed(intendedIndices: List[String], readIndices: List[String]) extends Rejection

}

sealed trait ReadingFailure

object ReadingFailure {

  case object CannotReadTable extends ReadingFailure

  final case class NotWhereEsReportedIt(indexList: String) extends ReadingFailure

  final case class UnsupportedIndexList(indexList: String) extends ReadingFailure

  final case class IndexListNotWrittenOnce(indexList: String) extends ReadingFailure

  final case class PatternNotWrittenOnce(commandName: String, indexNameWildcard: String) extends ReadingFailure

  final case class CommandTakesNoIndexList(commandName: String) extends ReadingFailure

  final case class OverlappingIndexLists(oneWrittenAs: String, otherWrittenAs: String) extends ReadingFailure

}
