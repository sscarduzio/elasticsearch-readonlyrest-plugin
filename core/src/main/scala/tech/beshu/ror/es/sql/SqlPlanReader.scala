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

import org.joor.ReflectException
import tech.beshu.ror.es.sql.SqlPlanReader.{PlanFailure, SqlPlan}

import java.lang.reflect.InvocationTargetException
import scala.util.{Failure, Success, Try}

trait SqlPlanReader {

  def planIn(query: String): Either[PlanFailure, SqlPlan]

}

object SqlPlanReader {

  sealed trait SqlPlan

  object SqlPlan {

    final case class Statement(tableIdentifiers: List[Any]) extends SqlPlan

    final case class Command(underlyingObject: Any) extends SqlPlan

  }

  sealed trait PlanFailure

  object PlanFailure {

    final case class RejectedByEs(cause: Throwable) extends PlanFailure

    final case class CannotReadPlan(cause: Throwable) extends PlanFailure

  }

}

abstract class ReflectiveSqlPlanReader(
    implicit classLoader: ClassLoader
) extends SqlPlanReader {

  protected def parsed(query: String): AnyRef

  protected def tableIdentifiersIn(plan: AnyRef): List[Any]

  override final def planIn(query: String): Either[PlanFailure, SqlPlan] =
    Try(parsed(query)) match {
      case Success(statement) =>
        Try(planOf(statement)).toEither.left.map(PlanFailure.CannotReadPlan.apply)
      case Failure(ex: ReflectException) if ex.getCause.isInstanceOf[InvocationTargetException] =>
        Left(PlanFailure.RejectedByEs(Option(ex.getCause.getCause).getOrElse(ex)))
      case Failure(ex) =>
        Left(PlanFailure.CannotReadPlan(ex))
    }

  private def planOf(statement: AnyRef): SqlPlan =
    if (isCommand(statement)) SqlPlan.Command(statement)
    else SqlPlan.Statement(tableIdentifiersIn(statement))

  private def isCommand(statement: AnyRef): Boolean =
    classLoader
      .loadClass("org.elasticsearch.xpack.sql.plan.logical.command.Command")
      .isInstance(statement)

}
