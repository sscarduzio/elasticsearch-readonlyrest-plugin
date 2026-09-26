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
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestId, RequestedIndex}
import tech.beshu.ror.es.esql.EsqlQueryIndicesReader.QueryIndices
import tech.beshu.ror.es.query.{IndexPatternInQuery, SourceLocation}
import tech.beshu.ror.syntax.*

class QueryTest extends AnyWordSpec {

  "An ES|QL query" when {
    "built from a raw query" should {
      "read the indices a source command asks for" in {
        val query = queryFrom("FROM logs-1,logs-2 | LIMIT 10", from("FROM logs-1,logs-2", "logs-1,logs-2"))

        query.indices.toList.map(_.name.stringify).sorted shouldBe List("logs-1", "logs-2")
      }
      "read the indices Elasticsearch picks for a PROMQL command that names none" in {
        val query = queryFrom("PROMQL step=1m rate(v)", from("PROMQL step=1m rate(v)", "metrics-*"))

        query.indices.toList.map(_.name.stringify) shouldBe List("metrics-*")
      }
      "have no indices when it names no index list" in {
        queryFrom("ROW a = 1") shouldBe Query.WithoutIndices("ROW a = 1")
      }
      "be unreadable when Elasticsearch cannot parse it" in {
        Query.from("FROM", readerFailingWith(new IllegalArgumentException("cannot parse"))) shouldBe
          Query.Unreadable("FROM", Rejection.CannotParseQuery)
      }
      "be unreadable when its index list is not where Elasticsearch reported it" in {
        val reader = readerReading(_ =>
          QueryIndices(List(IndexPatternInQuery("logs-1", SourceLocation(1, 0), "FROM elsewhere")), lookupJoins = Nil)
        )

        Query.from("FROM logs-1 | LIMIT 10", reader) shouldBe
          Query.Unreadable(
            "FROM logs-1 | LIMIT 10",
            Rejection.CannotExtractIndices(ReadingFailure.NotWhereEsReportedIt("logs-1"))
          )
      }
      "be unreadable when Elasticsearch points at index lists that share text" in {
        val query = queryFrom("FROM a, b | LIMIT 10", from("FROM a, b", "a,b"), from("FROM a", "a"))

        query shouldBe Query.Unreadable(
          "FROM a, b | LIMIT 10",
          Rejection.CannotExtractIndices(ReadingFailure.OverlappingIndexLists("a", "a, b"))
        )
      }
      "ask for all indices when it is unreadable" in {
        Query.Unreadable("FROM", Rejection.CannotParseQuery).indices shouldBe allIndices.toList.toCovariantSet
      }
      "ask for all indices when it names no index list" in {
        Query.WithoutIndices("ROW a = 1").indices shouldBe allIndices.toList.toCovariantSet
      }
    }
    "narrowed down" should {
      "rewrite the index list and confirm it with Elasticsearch" in {
        narrow(
          query = "FROM logs-* | LIMIT 10",
          allowed = allowed("logs-1"),
          reads = Map(
            "FROM logs-* | LIMIT 10" -> List(from("FROM logs-*", "logs-*")),
            "FROM logs-1 | LIMIT 10" -> List(from("FROM logs-1", "logs-1"))
          )
        ) shouldBe Right("FROM logs-1 | LIMIT 10")
      }
      "rewrite the index list written after a character that takes two UTF-16 units" in {
        narrow(
          query = "/* 😀 */ FROM logs-* | LIMIT 10",
          allowed = allowed("logs-1"),
          reads = Map(
            "/* 😀 */ FROM logs-* | LIMIT 10" -> List(from("FROM logs-*", "logs-*")),
            "/* 😀 */ FROM logs-1 | LIMIT 10" -> List(from("FROM logs-1", "logs-1"))
          )
        ) shouldBe Right("/* 😀 */ FROM logs-1 | LIMIT 10")
      }
      "write an index parameter into a PROMQL command that leans on the Elasticsearch default" in {
        narrow(
          query = "PROMQL step=1m rate(v)",
          allowed = allowed("metrics-1"),
          reads = Map(
            "PROMQL step=1m rate(v)" -> List(from("PROMQL step=1m rate(v)", "metrics-*")),
            "PROMQL index=metrics-1 step=1m rate(v)" -> List(from("metrics-1", "metrics-1"))
          )
        ) shouldBe Right("PROMQL index=metrics-1 step=1m rate(v)")
      }
      "rewrite a FORK query, whose forked relation Elasticsearch reports once for each branch" in {
        narrow(
          query = "FROM logs* | FORK (WHERE a > 1) (WHERE a < 1)",
          allowed = allowed("logs-1"),
          reads = Map(
            "FROM logs* | FORK (WHERE a > 1) (WHERE a < 1)" ->
              List(from("FROM logs*", "logs*"), from("FROM logs*", "logs*")),
            "FROM logs-1 | FORK (WHERE a > 1) (WHERE a < 1)" ->
              List(from("FROM logs-1", "logs-1"), from("FROM logs-1", "logs-1"))
          )
        ) shouldBe Right("FROM logs-1 | FORK (WHERE a > 1) (WHERE a < 1)")
      }
      "stay as written when the ACL allows exactly the indices it asks for" in {
        val reader = readerOf(Map("FROM logs-1 | LIMIT 10" -> List(from("FROM logs-1", "logs-1"))))
        val query = Query.from("FROM logs-1 | LIMIT 10", reader)

        Query.narrowed(query, allowed("logs-1"), reader) shouldBe Right(query)
      }
      "stay as written when it names no index list" in {
        val query = Query.WithoutIndices("ROW a = 1")

        Query.narrowed(query, allowed("logs-1"), readerOf(Map.empty)) shouldBe Right(query)
      }
      "be rejected when Elasticsearch reads no index list out of the rewrite" in {
        narrow(
          query = "FROM logs-* | LIMIT 10",
          allowed = allowed("logs-1"),
          reads = Map(
            "FROM logs-* | LIMIT 10" -> List(from("FROM logs-*", "logs-*")),
            "FROM logs-1 | LIMIT 10" -> List.empty
          )
        ) shouldBe Left(Rejection.SubstitutionNotConfirmed(List("logs-1"), List.empty))
      }
      "be rejected when Elasticsearch reads the rewrite as naming indices the rewritten query does not hold" in {
        narrow(
          query = "FROM logs-* | LIMIT 10",
          allowed = allowed("logs-1"),
          reads = Map(
            "FROM logs-* | LIMIT 10" -> List(from("FROM logs-*", "logs-*")),
            "FROM logs-1 | LIMIT 10" -> List(from("FROM logs-1", "logs-1,logs-2"))
          )
        ) shouldBe Left(
          Rejection.RewriteNotConfirmed(List("logs-1"), ReadingFailure.NotWhereEsReportedIt("logs-1,logs-2"))
        )
      }
      "be rejected when Elasticsearch cannot parse the rewrite" in {
        val reader = new StubReader({
          case "FROM logs-* | LIMIT 10" =>
            Right(fromSourcesOnly(List(from("FROM logs-*", "logs-*").at("FROM logs-* | LIMIT 10"))))
          case _ =>
            Left(new IllegalArgumentException("cannot parse"))
        })

        Query.narrowed(Query.from("FROM logs-* | LIMIT 10", reader), allowed("logs-1"), reader) shouldBe
          Left(Rejection.CannotParseRewrittenQuery(List("logs-1")))
      }
      "be rejected when it is unreadable and the ACL narrowed the indices down" in {
        Query.narrowed(
          Query.Unreadable("FROM", Rejection.CannotParseQuery),
          allowed("logs-1"),
          readerOf(Map.empty)
        ) shouldBe
          Left(Rejection.CannotParseQuery)
      }
      "stay as written when it is unreadable and the ACL narrowed nothing" in {
        val query = Query.Unreadable("FROM", Rejection.CannotParseQuery)

        Query.narrowed(query, allIndices, readerOf(Map.empty)) shouldBe Right(query)
      }
    }
  }

  private implicit val requestId: RequestId = RequestId("test")

  private val allIndices: NonEmptyList[RequestedIndex[ClusterIndexName]] =
    NonEmptyList.one(RequestedIndex(ClusterIndexName.Local.wildcard, excluded = false))

  private def queryFrom(query: String, reported: ReadWrittenAs*): Query =
    Query.from(query, readerReading(q => fromSourcesOnly(reported.toList.map(_.at(q)))))

  private def narrow(
      query: String,
      allowed: NonEmptyList[RequestedIndex[ClusterIndexName]],
      reads: Map[String, List[ReadWrittenAs]]
  ): Either[Rejection, String] = {
    val reader = readerOf(reads)
    Query.narrowed(Query.from(query, reader), allowed, reader).map(_.stringify)
  }

  private def readerOf(reads: Map[String, List[ReadWrittenAs]]): EsqlQueryIndicesReader =
    new StubReader({
      case q if reads.contains(q) => Right(fromSourcesOnly(reads(q).map(_.at(q))))
      case q                      => throw new IllegalStateException(s"unexpected query: $q")
    })

  private def readerReading(reads: String => QueryIndices): EsqlQueryIndicesReader =
    new StubReader(query => Right(reads(query)))

  private def readerFailingWith(cause: Throwable): EsqlQueryIndicesReader =
    new StubReader(_ => Left(cause))

  private def from(writtenText: String, indexPattern: String): ReadWrittenAs =
    ReadWrittenAs(writtenText, indexPattern)

  private def fromSourcesOnly(indexPatterns: List[IndexPatternInQuery]): QueryIndices =
    QueryIndices(indexPatterns, lookupJoins = Nil)

  private def allowed(names: String*): NonEmptyList[RequestedIndex[ClusterIndexName]] =
    NonEmptyList.fromListUnsafe(names.toList.flatMap(RequestedIndex.fromString))

  private final case class ReadWrittenAs(writtenText: String, indexPattern: String) {

    def at(query: String): IndexPatternInQuery = {
      val offset = query.indexOf(writtenText)
      val before = query.take(offset)
      val lineStart = before.lastIndexOf('\n') + 1
      IndexPatternInQuery(
        reportedIndexList = indexPattern,
        writtenAt = SourceLocation(
          line = before.count(_ == '\n') + 1,
          column = if (offset < 0) offset - lineStart else query.codePointCount(lineStart, offset)
        ),
        writtenText = writtenText
      )
    }

  }

  private final class StubReader(reads: String => Either[Throwable, QueryIndices]) extends EsqlQueryIndicesReader {

    override protected def queryIndicesFrom(query: String): Either[Throwable, QueryIndices] = reads(query)
  }

}
