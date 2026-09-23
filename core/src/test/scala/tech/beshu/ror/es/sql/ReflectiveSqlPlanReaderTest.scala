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

import org.elasticsearch.xpack.sql.plan.logical.command.ShowTables
import org.joor.Reflect.on
import org.scalatest.Inside.inside
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.es.sql.SqlPlanReader.{PlanFailure, SqlPlan}

class ReflectiveSqlPlanReaderTest extends AnyWordSpec {

  "A reflective SQL plan reader" should {
    "read the tables of a statement" in {
      readerParsingTo(new Select("library")).planIn("q") shouldBe Right(SqlPlan.Statement(List("library")))
    }
    "read a command as a command" in {
      val command = new ShowTables("library")

      readerParsingTo(command).planIn("q") shouldBe Right(SqlPlan.Command(command))
    }
    "tell that Elasticsearch rejected the query when its parser throws" in {
      val parseError = new IllegalArgumentException("line 1:8: mismatched input")

      readerOf(query => on(new ThrowingParser(parseError)).call("createStatement", query).get[AnyRef]())
        .planIn("SELECT") shouldBe Left(PlanFailure.RejectedByEs(parseError))
    }
    "tell that it cannot read the plan when the parser has no such method" in {
      inside(readerOf(query => on(new Select("library")).call("createStatement", query).get[AnyRef]()).planIn("q")) {
        case Left(PlanFailure.CannotReadPlan(_)) =>
      }
    }
    "tell that it cannot read the plan when the pre-analysis fails" in {
      val reader = new ReflectiveSqlPlanReader()(
        using getClass.getClassLoader
      ) {
        override protected def parsed(query: String): AnyRef = new Select("library")
        override protected def tableIdentifiersIn(plan: AnyRef): List[Any] = on(plan).get[List[Any]]("indices")
      }

      inside(reader.planIn("q")) { case Left(PlanFailure.CannotReadPlan(_)) => }
    }
  }

  private def readerParsingTo(statement: AnyRef): SqlPlanReader = readerOf(_ => statement)

  private def readerOf(parse: String => AnyRef): SqlPlanReader =
    new ReflectiveSqlPlanReader()(
      using getClass.getClassLoader
    ) {
      override protected def parsed(query: String): AnyRef = parse(query)
      override protected def tableIdentifiersIn(plan: AnyRef): List[Any] = List(on(plan).get[String]("table"))
    }

  private final class Select(val table: String)

  private final class ThrowingParser(error: Throwable) {
    def createStatement(query: String): AnyRef = throw error
  }

}
