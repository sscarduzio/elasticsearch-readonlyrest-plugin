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
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestId, RequestedIndex}
import tech.beshu.ror.es.sql.SqlQueryIndicesReader.{QueryIndices, ReadError}

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
      "read every index of a list written with spaces" in {
        val query = queryFrom("""SELECT name FROM "a, b"""", select(""""a, b"""" -> "a, b"))

        query.indices.toList.map(_.name.stringify).sorted shouldBe List("a", "b")
      }
      "not locate an index list inside a longer name" in {
        queryFrom("SHOW COLUMNS IN self:library", showColumns(index = "library")) shouldBe
          Query.Unreadable(
            "SHOW COLUMNS IN self:library",
            Rejection.CannotExtractIndices(ReadingFailure.IndexListNotWrittenOnce("library"))
          )
      }
      "have no index list when the statement names no table" in {
        queryFrom("SELECT 1 + 1", EsPlan.Statement(Nil)) shouldBe Query.WithoutIndices("SELECT 1 + 1")
      }
      "be unreadable when Elasticsearch cannot parse it" in {
        Query.from("SELECT", readerFailingWith(esRejection)) shouldBe
          Query.Unreadable("SELECT", Rejection.CannotParseQuery)
      }
      "have no index list when it is the empty query of a cursor request" in {
        Query.from("", readerFailingWith(esRejection)) shouldBe Query.WithoutIndices("")
      }
      "be unreadable when ReadonlyREST cannot read the plan Elasticsearch built" in {
        Query.from("SELECT * FROM library", readerFailingWith(readingFailure)) shouldBe
          Query.Unreadable("SELECT * FROM library", Rejection.CannotReadQuery)
      }
      "be unreadable when the table is not where Elasticsearch reported it" in {
        val query = """SELECT name FROM "bookstore""""

        Query.from(query, readerReading(_ => select("elsewhere" -> "bookstore")(query))) shouldBe
          Query.Unreadable(query, Rejection.CannotExtractIndices(ReadingFailure.NotWhereEsReportedIt("bookstore")))
      }
      "be unreadable when both ways of counting the column point at the table" in {
        val query = """SELECT '😀' AS e FROM aaa"""

        Query.from(query, readerReading(selectCountingUtf16Units("aa" -> "aa"))) shouldBe
          Query.Unreadable(query, Rejection.CannotExtractIndices(ReadingFailure.NotWhereEsReportedIt("aa")))
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
      "rewrite the table written after a character that takes two UTF-16 units, columns counted in code points" in {
        narrow(
          query = """SELECT '😀' AS e, name FROM "book*"""",
          allowed = allowed("bookstore"),
          reads = Map(
            """SELECT '😀' AS e, name FROM "book*"""" -> select(""""book*"""" -> "book*"),
            """SELECT '😀' AS e, name FROM "bookstore"""" -> select(""""bookstore"""" -> "bookstore")
          )
        ) shouldBe Right("""SELECT '😀' AS e, name FROM "bookstore"""")
      }
      "rewrite the table written after a character that takes two UTF-16 units, columns counted in UTF-16 units" in {
        narrow(
          query = """SELECT '😀' AS e, name FROM "book*"""",
          allowed = allowed("bookstore"),
          reads = Map(
            """SELECT '😀' AS e, name FROM "book*"""" -> selectCountingUtf16Units(""""book*"""" -> "book*"),
            """SELECT '😀' AS e, name FROM "bookstore"""" -> selectCountingUtf16Units(""""bookstore"""" -> "bookstore")
          )
        ) shouldBe Right("""SELECT '😀' AS e, name FROM "bookstore"""")
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
          reads = Map("SELECT 1 + 1" -> EsPlan.Statement(Nil))
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
        ) shouldBe Left(Rejection.CannotExtractIndices(ReadingFailure.CommandTakesNoIndexList("SysColumns")))
      }
      "write the same index list into every table" in {
        narrow(
          query = """SELECT * FROM "a*" WHERE x IN (SELECT y FROM "b*")""",
          allowed = allowed("a1", "b1"),
          reads = Map(
            """SELECT * FROM "a*" WHERE x IN (SELECT y FROM "b*")""" -> select(""""a*"""" -> "a*", """"b*"""" -> "b*"),
            """SELECT * FROM "a1,b1" WHERE x IN (SELECT y FROM "a1,b1")""" -> select(""""a1,b1"""" -> "a1,b1")
          )
        ) shouldBe Right("""SELECT * FROM "a1,b1" WHERE x IN (SELECT y FROM "a1,b1")""")
      }
      "be rejected when Elasticsearch reads the rewrite as something else" in {
        narrow(
          query = """SELECT name FROM "book*"""",
          allowed = allowed("bookstore"),
          reads = Map(
            """SELECT name FROM "book*"""" -> select(""""book*"""" -> "book*"),
            """SELECT name FROM "bookstore"""" -> select("name" -> "name")
          )
        ) shouldBe Left(Rejection.SubstitutionNotConfirmed(List("bookstore"), List("name")))
      }
      "be rejected when the rewrite is not where Elasticsearch reported it" in {
        narrow(
          query = """SELECT name FROM "book*"""",
          allowed = allowed("bookstore"),
          reads = Map(
            """SELECT name FROM "book*"""" -> select(""""book*"""" -> "book*"),
            """SELECT name FROM "bookstore"""" -> select(""""bookstore"""" -> "bookstore,library")
          )
        ) shouldBe Left(
          Rejection.RewriteNotConfirmed(List("bookstore"), ReadingFailure.NotWhereEsReportedIt("bookstore,library"))
        )
      }
      "be rejected when Elasticsearch cannot parse the rewrite" in {
        val query = """SELECT name FROM "book*""""
        val reader = new StubReader({
          case q if q == query => Right(select(""""book*"""" -> "book*")(q))
          case _               => Left(esRejection)
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
        Query.Unreadable("SELECT", Rejection.CannotReadQuery).narrowedTo(allowed("bookstore")) shouldBe
          Left(Rejection.CannotReadQuery)
      }
      "be rejected when Elasticsearch cannot parse it and the ACL narrowed the indices down" in {
        Query.from("SELECT FROM", readerFailingWith(esRejection)).narrowedTo(allowed("bookstore")) shouldBe
          Left(Rejection.CannotParseQuery)
      }
      "stay as written when Elasticsearch cannot parse it and the ACL allows all indices" in {
        Query.from("SELECT FROM", readerFailingWith(esRejection)).narrowedTo(allowed("*")).map(_.stringify) shouldBe
          Right("SELECT FROM")
      }
      "stay as written when it is the empty query of a cursor request" in {
        Query.from("", readerFailingWith(esRejection)).narrowedTo(allowed("bookstore")).map(_.stringify) shouldBe
          Right("")
      }
    }
  }

  private implicit val requestId: RequestId = RequestId("test")

  private val esRejection = ReadError.RejectedByEs(new IllegalArgumentException("cannot parse"))

  private val readingFailure = ReadError.PlanNotRead(new IllegalStateException("no such field"))

  private def queryFrom(query: String, plan: String => EsPlan): Query =
    Query.from(query, readerReading(plan))

  private def queryFrom(query: String, plan: EsPlan): Query =
    Query.from(query, readerReading(_ => plan))

  private def narrow(
      query: String,
      allowed: NonEmptyList[RequestedIndex[ClusterIndexName]],
      reads: Map[String, Any]
  ): Either[Rejection, String] =
    Query.from(query, readerOf(reads)).narrowedTo(allowed).map(_.stringify)

  private def readerOf(reads: Map[String, Any]): SqlQueryIndicesReader =
    new StubReader({
      case q if reads.contains(q) =>
        reads(q) match {
          case plan: EsPlan                          => Right(plan)
          case planOf: (String => EsPlan) @unchecked => Right(planOf(q))
        }
      case q => throw new IllegalStateException(s"unexpected query: $q")
    })

  private def readerReading(plan: String => EsPlan): SqlQueryIndicesReader =
    new StubReader(query => Right(plan(query)))

  private def readerFailingWith(failure: ReadError): SqlQueryIndicesReader =
    new StubReader(_ => Left(failure))

  private def allowed(names: String*): NonEmptyList[RequestedIndex[ClusterIndexName]] =
    NonEmptyList.fromListUnsafe(names.toList.flatMap(RequestedIndex.fromString))

  private def select(tables: (String, String)*): String => EsPlan =
    selectCountingColumns(inCodePoints, tables)

  private def selectCountingUtf16Units(tables: (String, String)*): String => EsPlan =
    selectCountingColumns(inUtf16Units, tables)

  private def selectCountingColumns(columnUnits: ColumnUnits, tables: Seq[(String, String)]): String => EsPlan =
    query =>
      EsPlan.Statement(tables.toList.map { case (written, reported) =>
        tableIdentifier(query, written, reported, columnUnits)
      })

  private type ColumnUnits = (String, Int, Int) => Int

  private val inCodePoints: ColumnUnits = (query, lineStart, offset) => query.codePointCount(lineStart, offset)

  private val inUtf16Units: ColumnUnits = (_, lineStart, offset) => offset - lineStart

  private def showTables(index: String = null, wildcard: String = null): String => EsPlan =
    _ => EsPlan.Command(new ShowTables(index, likePattern(wildcard)))

  private def showColumns(index: String = null, wildcard: String = null): String => EsPlan =
    _ => EsPlan.Command(new ShowColumns(index, likePattern(wildcard)))

  private def showFunctions(): String => EsPlan =
    _ => EsPlan.Command(new ShowFunctions(likePattern("A*")))

  private def sysColumns(wildcard: String): String => EsPlan =
    _ => EsPlan.Command(new SysColumns(null, likePattern(wildcard)))

  private def likePattern(wildcard: String): LikePattern =
    Option(wildcard).map(new LikePattern(_)).orNull

  private def tableIdentifier(
      query: String,
      writtenText: String,
      reportedIndexList: String,
      columnUnits: ColumnUnits
  ): Any = {
    val offset = query.indexOf(writtenText)
    val before = query.take(offset)
    val lineStart = before.lastIndexOf('\n') + 1
    new TableIdentifier(
      reportedIndexList,
      new Source(
        writtenText,
        new Location(before.count(_ == '\n') + 1, if (offset < 0) 0 else columnUnits(query, lineStart, offset) + 1)
      )
    )
  }

  private sealed trait EsPlan

  private object EsPlan {
    final case class Statement(tableIdentifiers: List[Any]) extends EsPlan
    final case class Command(underlyingObject: Any) extends EsPlan
  }

  private final class StubReader(reads: String => Either[ReadError, EsPlan]) extends SqlQueryIndicesReader {

    override private[sql] def queryIndicesFrom(query: String): Either[ReadError, QueryIndices] =
      reads(query).flatMap {
        case EsPlan.Statement(tableIdentifiers) =>
          tableIdentifiers
            .traverse(EsSqlObjects.indexPatternIn)
            .map(QueryIndices.StatementTables.apply)
            .toRight(ReadError.IndicesNotLocated(ReadingFailure.CannotReadTable))
        case EsPlan.Command(command) =>
          Right(QueryIndices.CommandIndices(EsSqlObjects.selectorOf(command)))
      }

  }

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
