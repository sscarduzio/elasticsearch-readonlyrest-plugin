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
import tech.beshu.ror.es.sql.CommandSelector.LiteralIndexList
import tech.beshu.ror.es.sql.SqlQueryIndicesReader.{QueryIndices, ReadError, SourceLocation, TableInQuery}

class ReflectiveSqlQueryIndicesReaderTest extends AnyWordSpec {

  "A reflective SQL query indices reader" should {
    "read the tables of a statement" in {
      readerParsingTo(new Select(new TableIdentifier("library"))).queryIndicesFrom("q") shouldBe
        Right(QueryIndices.StatementTables(List(TableInQuery("library", SourceLocation(1, 14), "library"))))
    }
    "read the index list of a command" in {
      readerParsingTo(new ShowTables("library")).queryIndicesFrom("q") shouldBe
        Right(QueryIndices.CommandIndices(LiteralIndexList("library")))
    }
    "tell that Elasticsearch rejected the query when its parser throws" in {
      val parseError = new IllegalArgumentException("line 1:8: mismatched input")

      readerOf(query => on(new ThrowingParser(parseError)).call("createStatement", query).get[AnyRef]())
        .queryIndicesFrom("SELECT") shouldBe Left(ReadError.RejectedByEs(parseError))
    }
    "tell that it cannot read the plan when the parser has no such method" in {
      inside(
        readerOf(query => on(new Select(new TableIdentifier("library"))).call("createStatement", query).get[AnyRef]())
          .queryIndicesFrom("q")
      ) { case Left(ReadError.PlanNotRead(_)) =>
      }
    }
    "tell that it cannot read the plan when the pre-analysis fails" in {
      val reader = new ReflectiveSqlQueryIndicesReader()(
        using getClass.getClassLoader
      ) {
        override protected def parsed(query: String): AnyRef = new Select(new TableIdentifier("library"))
        override protected def tableIdentifiersIn(plan: AnyRef): List[Any] = on(plan).get[List[Any]]("indices")
      }

      inside(reader.queryIndicesFrom("q")) { case Left(ReadError.PlanNotRead(_)) => }
    }
    "tell that it cannot read a table Elasticsearch reported without its place in the query" in {
      val reader = new ReflectiveSqlQueryIndicesReader()(
        using getClass.getClassLoader
      ) {
        override protected def parsed(query: String): AnyRef = new Select(new TableIdentifier("library"))
        override protected def tableIdentifiersIn(plan: AnyRef): List[Any] = List("library")
      }

      reader.queryIndicesFrom("q") shouldBe Left(ReadError.IndicesNotLocated(ReadingFailure.CannotReadTable))
    }
  }

  private def readerParsingTo(statement: AnyRef): ReflectiveSqlQueryIndicesReader = readerOf(_ => statement)

  private def readerOf(parse: String => AnyRef): ReflectiveSqlQueryIndicesReader =
    new ReflectiveSqlQueryIndicesReader()(
      using getClass.getClassLoader
    ) {
      override protected def parsed(query: String): AnyRef = parse(query)
      override protected def tableIdentifiersIn(plan: AnyRef): List[Any] = List(on(plan).get[TableIdentifier]("table"))
    }

  private final class Select(val table: TableIdentifier)

  private final class Location {
    def getLineNumber: Int = 1
    def getColumnNumber: Int = 15
  }

  private final class Source(writtenText: String) {
    def text: String = writtenText
    def source: Location = new Location
  }

  private final class TableIdentifier(index: String) {
    def qualifiedIndex: String = index
    def source: Source = new Source(index)
  }

  private final class ThrowingParser(error: Throwable) {
    def createStatement(query: String): AnyRef = throw error
  }

}
