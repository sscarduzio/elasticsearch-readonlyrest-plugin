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

import cats.syntax.traverse.*
import org.joor.ReflectException
import tech.beshu.ror.es.query.IndexPatternInQuery
import tech.beshu.ror.es.sql.SqlQueryIndicesReader.{QueryIndices, ReadError}

import java.lang.reflect.InvocationTargetException
import scala.util.{Failure, Success, Try}

trait SqlQueryIndicesReader {

  private[sql] final def indicesIn(query: String): Either[ReadError, List[LocatedIndexList]] =
    for {
      indices <- queryIndicesFrom(query)
      indexLists <- IndexListLocator.locatedIn(query, indices).left.map(ReadError.IndicesNotLocated.apply)
    } yield indexLists

  private[sql] def queryIndicesFrom(query: String): Either[ReadError, QueryIndices]

}

object SqlQueryIndicesReader {

  private[sql] sealed trait QueryIndices

  private[sql] object QueryIndices {

    /** The list is empty for a statement that reads no table, e.g. `SELECT 1 + 1`. */
    final case class StatementTables(tables: List[IndexPatternInQuery]) extends QueryIndices

    final case class CommandIndices(selector: CommandSelector) extends QueryIndices

  }

  private[sql] sealed trait ReadError

  private[sql] object ReadError {

    final case class RejectedByEs(cause: Throwable) extends ReadError

    final case class PlanNotRead(cause: Throwable) extends ReadError

    final case class IndicesNotLocated(failure: ReadingFailure) extends ReadError

  }

}

abstract class ReflectiveSqlQueryIndicesReader(
    implicit classLoader: ClassLoader
) extends SqlQueryIndicesReader {

  protected def parsed(query: String): AnyRef

  protected def tableIdentifiersIn(plan: AnyRef): List[Any]

  protected val commandClassName: String = "org.elasticsearch.xpack.sql.plan.logical.command.Command"

  override private[sql] final def queryIndicesFrom(query: String): Either[ReadError, QueryIndices] =
    Try(parsed(query)) match {
      case Success(statement) =>
        Try(queryIndicesIn(statement)).toEither.left.map(ReadError.PlanNotRead.apply).flatten
      case Failure(ex: ReflectException) if ex.getCause.isInstanceOf[InvocationTargetException] =>
        Left(ReadError.RejectedByEs(Option(ex.getCause.getCause).getOrElse(ex)))
      case Failure(ex) =>
        Left(ReadError.PlanNotRead(ex))
    }

  private def queryIndicesIn(statement: AnyRef): Either[ReadError, QueryIndices] =
    if (isCommand(statement)) Right(QueryIndices.CommandIndices(EsSqlObjects.selectorOf(statement)))
    else
      tableIdentifiersIn(statement)
        .traverse(EsSqlObjects.indexPatternIn(_).toEither)
        .map(QueryIndices.StatementTables.apply)
        .left
        .map(ReadError.PlanNotRead.apply)

  private def isCommand(statement: AnyRef): Boolean =
    classLoader
      .loadClass(commandClassName)
      .isInstance(statement)

}
