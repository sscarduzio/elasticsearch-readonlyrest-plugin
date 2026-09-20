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
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestId, RequestedIndex}
import tech.beshu.ror.es.sql.SqlPlanReader.SqlPlan

class QueryTest extends AnyWordSpec {

  "An SQL query" when {
    "built from a raw statement" should {
      "read the index a SELECT names" in {
        val query = queryFrom("""SELECT name FROM "bookstore"""", select(""""bookstore"""" -> "bookstore"))

        query.indices.toList.map(_.name.stringify).sorted shouldBe List("bookstore")
      }
      "read every index of a list a SELECT names" in {
        val query = queryFrom("""SELECT name FROM "a,b"""", select(""""a,b"""" -> "a,b"))

        query.indices.toList.map(_.name.stringify).sorted shouldBe List("a", "b")
      }
      "read the remote cluster a SELECT names without quoting it" in {
        val query = queryFrom("SELECT name FROM self:library", select("self:library" -> "self:library"))

        query.indices.toList.map(_.name.stringify).sorted shouldBe List("self:library")
      }
      "read the index a command names" in {
        val query = queryFrom("""SHOW COLUMNS IN "library"""", showColumns(index = "library"))

        query.indices.toList.map(_.name.stringify).sorted shouldBe List("library")
      }
      "read the pattern a command matches index names with" in {
        val query = queryFrom("SHOW TABLES LIKE 'book%'", showTables(wildcard = "book*"))

        query.indices.toList.map(_.name.stringify).sorted shouldBe List("book*")
      }
      "read all indices when a command names none" in {
        val query = queryFrom("SHOW TABLES", showTables())

        query.indices.toList.map(_.name.stringify).sorted shouldBe List("*")
      }
      "have no index list when the command matches something other than index names" in {
        queryFrom("SHOW FUNCTIONS LIKE 'A%'", showFunctions()) shouldBe
          Query.WithoutIndices("SHOW FUNCTIONS LIKE 'A%'")
      }
      "have no index list when the statement names no table" in {
        queryFrom("SELECT 1 + 1", SqlPlan.Statement(Nil)) shouldBe Query.WithoutIndices("SELECT 1 + 1")
      }
      "be unreadable when Elasticsearch cannot parse it" in {
        Query.from("SELECT", readerFailingWith(new IllegalArgumentException("cannot parse"))) shouldBe
          Query.Unreadable("SELECT", Rejection.CannotParseQuery)
      }
      "be unreadable when the table is not where Elasticsearch reported it" in {
        val query = """SELECT name FROM "bookstore""""

        Query.from(query, readerReading(_ => select("elsewhere" -> "bookstore")(query))) shouldBe
          Query.Unreadable(query, Rejection.CannotLocateIndexList(ReadingFailure.NotWhereEsReportedIt("bookstore")))
      }
    }
    "narrowed down" should {
      "rewrite the table Elasticsearch pointed at, quoted" in {
        narrow(
          query = """SELECT name FROM "book*"""",
          allowed = allowed("bookstore"),
          reads = Map(
            """SELECT name FROM "book*"""" -> select(""""book*"""" -> "book*"),
            """SELECT name FROM "bookstore"""" -> select(""""bookstore"""" -> "bookstore")
          )
        ) shouldBe Right("""SELECT name FROM "bookstore"""")
      }
      "quote a list written into a table that was not quoted" in {
        narrow(
          query = "SELECT name FROM everything",
          allowed = allowed("bookstore", "library"),
          reads = Map(
            "SELECT name FROM everything" -> select("everything" -> "everything"),
            """SELECT name FROM "bookstore,library"""" -> select(""""bookstore,library"""" -> "bookstore,library")
          )
        ) shouldBe Right("""SELECT name FROM "bookstore,library"""")
      }
      "leave a literal that reads like the table alone" in {
        narrow(
          query = """SELECT name FROM "boo*" WHERE name = 'boo*'""",
          allowed = allowed("bookstore"),
          reads = Map(
            """SELECT name FROM "boo*" WHERE name = 'boo*'""" -> select(""""boo*"""" -> "boo*"),
            """SELECT name FROM "bookstore" WHERE name = 'boo*'""" ->
              select(""""bookstore"""" -> "bookstore")
          )
        ) shouldBe Right("""SELECT name FROM "bookstore" WHERE name = 'boo*'""")
      }
      "leave text written before the FROM keyword alone" in {
        narrow(
          query = """SELECT 'FROMAGE' AS lit, name FROM "boo*"""",
          allowed = allowed("bookstore"),
          reads = Map(
            """SELECT 'FROMAGE' AS lit, name FROM "boo*"""" -> select(""""boo*"""" -> "boo*"),
            """SELECT 'FROMAGE' AS lit, name FROM "bookstore"""" -> select(""""bookstore"""" -> "bookstore")
          )
        ) shouldBe Right("""SELECT 'FROMAGE' AS lit, name FROM "bookstore"""")
      }
      "rewrite a statement that writes the FROM keyword in lower case" in {
        narrow(
          query = """select 'boo*' as lit, name from "boo*"""",
          allowed = allowed("bookstore"),
          reads = Map(
            """select 'boo*' as lit, name from "boo*"""" -> select(""""boo*"""" -> "boo*"),
            """select 'boo*' as lit, name from "bookstore"""" -> select(""""bookstore"""" -> "bookstore")
          )
        ) shouldBe Right("""select 'boo*' as lit, name from "bookstore"""")
      }
      "leave a command that matches something other than index names as it was written" in {
        narrow(
          query = "SHOW FUNCTIONS LIKE 'A%'",
          allowed = allowed("bookstore"),
          reads = Map("SHOW FUNCTIONS LIKE 'A%'" -> showFunctions())
        ) shouldBe Right("SHOW FUNCTIONS LIKE 'A%'")
      }
      "leave a statement that names no table as it was written" in {
        narrow(
          query = "SELECT 1 + 1",
          allowed = allowed("bookstore"),
          reads = Map("SELECT 1 + 1" -> SqlPlan.Statement(Nil))
        ) shouldBe Right("SELECT 1 + 1")
      }
      "write an index list into a command that names none" in {
        narrow(
          query = "SHOW TABLES",
          allowed = allowed("bookstore"),
          reads = Map(
            "SHOW TABLES" -> showTables(),
            """SHOW TABLES "bookstore"""" -> showTables(index = "bookstore")
          )
        ) shouldBe Right("""SHOW TABLES "bookstore"""")
      }
      "write an index list over the LIKE clause of a command that matches index names" in {
        narrow(
          query = "SHOW TABLES LIKE 'book%'",
          allowed = allowed("bookstore"),
          reads = Map(
            "SHOW TABLES LIKE 'book%'" -> showTables(wildcard = "book*"),
            """SHOW TABLES "bookstore"""" -> showTables(index = "bookstore")
          )
        ) shouldBe Right("""SHOW TABLES "bookstore"""")
      }
      "write an index list over the LIKE clause of a command that names columns" in {
        narrow(
          query = "SHOW COLUMNS IN LIKE 'lib%'",
          allowed = allowed("library"),
          reads = Map(
            "SHOW COLUMNS IN LIKE 'lib%'" -> showColumns(wildcard = "lib*"),
            """SHOW COLUMNS IN "library"""" -> showColumns(index = "library")
          )
        ) shouldBe Right("""SHOW COLUMNS IN "library"""")
      }
      "be rejected when the command that matches index names takes no index list" in {
        narrow(
          query = "SYS COLUMNS TABLE LIKE 'lib%'",
          allowed = allowed("bookstore"),
          reads = Map("SYS COLUMNS TABLE LIKE 'lib%'" -> sysColumns(wildcard = "lib*"))
        ) shouldBe Left(Rejection.CannotLocateIndexList(ReadingFailure.CommandTakesNoIndexList("SysColumns")))
      }
      "be rejected when Elasticsearch reads the rewrite as something else" in {
        narrow(
          query = """SELECT name FROM "book*"""",
          allowed = allowed("bookstore"),
          reads = Map(
            """SELECT name FROM "book*"""" -> select(""""book*"""" -> "book*"),
            """SELECT name FROM "bookstore"""" -> select(""""bookstore"""" -> "bookstore,library")
          )
        ) shouldBe Left(Rejection.SubstitutionNotConfirmed(List("bookstore"), List("bookstore", "library")))
      }
      "be rejected when Elasticsearch cannot parse the rewrite" in {
        val query = """SELECT name FROM "book*""""
        val reader = new StubReader({
          case q if q == query => Right(select(""""book*"""" -> "book*")(q))
          case _               => Left(new IllegalArgumentException("cannot parse"))
        })

        Query.from(query, reader).narrowedTo(allowed("bookstore")) shouldBe
          Left(Rejection.CannotParseRewrittenQuery(List("bookstore")))
      }
      "stay as written when the ACL narrowed nothing" in {
        val query = """SELECT name FROM "bookstore""""

        narrow(
          query = query,
          allowed = allowed("bookstore"),
          reads = Map(query -> select(""""bookstore"""" -> "bookstore"))
        ) shouldBe Right(query)
      }
      "be rejected when it is unreadable and the ACL narrowed the indices down" in {
        Query.Unreadable("SELECT", Rejection.CannotParseQuery).narrowedTo(allowed("bookstore")) shouldBe
          Left(Rejection.CannotParseQuery)
      }
    }
  }

  private implicit val requestId: RequestId = RequestId("test")

  private def queryFrom(query: String, plan: String => SqlPlan): Query =
    Query.from(query, readerReading(plan))

  private def queryFrom(query: String, plan: SqlPlan): Query =
    Query.from(query, readerReading(_ => plan))

  private def narrow(
      query: String,
      allowed: NonEmptyList[RequestedIndex[ClusterIndexName]],
      reads: Map[String, Any]
  ): Either[Rejection, String] =
    Query.from(query, readerOf(reads)).narrowedTo(allowed).map(_.stringify)

  private def readerOf(reads: Map[String, Any]): SqlPlanReader =
    new StubReader({
      case q if reads.contains(q) =>
        reads(q) match {
          case plan: SqlPlan                          => Right(plan)
          case planOf: (String => SqlPlan) @unchecked => Right(planOf(q))
        }
      case q => throw new IllegalStateException(s"unexpected query: $q")
    })

  private def readerReading(plan: String => SqlPlan): SqlPlanReader =
    new StubReader(query => Right(plan(query)))

  private def readerFailingWith(cause: Throwable): SqlPlanReader =
    new StubReader(_ => Left(cause))

  private def allowed(names: String*): NonEmptyList[RequestedIndex[ClusterIndexName]] =
    NonEmptyList.fromListUnsafe(names.toList.flatMap(RequestedIndex.fromString))

  private def select(tables: (String, String)*): String => SqlPlan =
    query =>
      SqlPlan.Statement(tables.toList.map { case (written, reported) => tableIdentifier(query, written, reported) })

  private def showTables(index: String = null, wildcard: String = null): String => SqlPlan =
    _ => SqlPlan.Command(new ShowTables(index, likePattern(wildcard)))

  private def showColumns(index: String = null, wildcard: String = null): String => SqlPlan =
    _ => SqlPlan.Command(new ShowColumns(index, likePattern(wildcard)))

  private def showFunctions(): String => SqlPlan =
    _ => SqlPlan.Command(new ShowFunctions(likePattern("A*")))

  private def sysColumns(wildcard: String): String => SqlPlan =
    _ => SqlPlan.Command(new SysColumns(null, likePattern(wildcard)))

  private def likePattern(wildcard: String): LikePattern =
    Option(wildcard).map(new LikePattern(_)).orNull

  private def tableIdentifier(query: String, writtenText: String, reportedIndexList: String): Any = {
    val offset = query.indexOf(writtenText)
    val before = query.take(offset)
    new TableIdentifier(
      reportedIndexList,
      new Source(
        writtenText,
        new Location(before.count(_ == '\n') + 1, offset - (before.lastIndexOf('\n') + 1) + 1)
      )
    )
  }

  private final class StubReader(reads: String => Either[Throwable, SqlPlan]) extends SqlPlanReader {
    override def planIn(query: String): Either[Throwable, SqlPlan] = reads(query)
  }

  /** The shapes Elasticsearch hands back, named and spelled the way its own classes are. */
  private final class Location(line: Int, column: Int) {
    def getLineNumber: Int = line
    def getColumnNumber: Int = column
  }

  private final class Source(writtenText: String, location: Location) {
    def text: String = writtenText
    def source: Location = location
  }

  private final class TableIdentifier(index: String, writtenAt: Source) {
    def qualifiedIndex: String = index
    def source: Source = writtenAt
  }

  private final class LikePattern(wildcard: String) {
    def asIndexNameWildcard: String = wildcard
  }

  private final class ShowTables(val index: String, val pattern: LikePattern)

  private final class ShowColumns(val index: String, val pattern: LikePattern)

  private final class SysColumns(val index: String, val pattern: LikePattern)

  private final class ShowFunctions(val pattern: LikePattern)

}
