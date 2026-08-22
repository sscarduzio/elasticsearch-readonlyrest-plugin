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
package tech.beshu.ror.unit.es.esql

import cats.data.NonEmptyList
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestedIndex}
import tech.beshu.ror.es.esql.*
import tech.beshu.ror.es.esql.EsqlIndexListReadingFailure.*

/**
 * Covers reading the index lists ES reported out of the query text and replacing them in one go, because that is
 * the contract: what ES hands ROR has to come back out of the query text as the same list, or the query is left
 * alone entirely.
 *
 * A reported relation is written the way ES's parser builds it - the raw text of the node it read the index list
 * from, plus that list normalized (`FROM a, b` reported as `a,b`).
 */
class EsqlIndexListReplacingTest extends AnyWordSpec {

  private val maskedIndex = """ROR_[A-Za-z0-9]{10}""".r

  "Reading and replacing the index lists of an ES|QL query" when {
    "a source command is used" should {
      "replace a wildcard with the allowed indices" in {
        rewrite("FROM logs-* | LIMIT 10", allowed("logs-1", "logs-2"), from("FROM logs-*", "logs-*")) should
          fullyMatch regex "FROM (logs-1,logs-2|logs-2,logs-1) \\| LIMIT 10"
      }
      "keep the query as it was written apart from the index list" in {
        rewrite("FROM bookshop | LIMIT 100", allowed("bookstore"), from("FROM bookshop", "bookshop")) shouldBe
          "FROM bookstore | LIMIT 100"
      }
      "replace a comma separated list written with spaces" in {
        rewrite("FROM a, b | LIMIT 10", allowed("a"), from("FROM a, b", "a,b")) shouldBe "FROM a | LIMIT 10"
      }
      "replace a comma separated list whose entries are quoted" in {
        rewrite("""FROM "a", b | LIMIT 10""", allowed("a"), from("""FROM "a", b""", "a,b")) shouldBe
          "FROM a | LIMIT 10"
      }
      "replace a single quoted index list holding a comma separated list" in {
        rewrite("""FROM "a,b" | LIMIT 10""", allowed("b"), from("""FROM "a,b"""", "a,b")) shouldBe
          "FROM b | LIMIT 10"
      }
      "leave a METADATA clause in place" in {
        rewrite(
          "FROM logs-* METADATA _index | LIMIT 10",
          allowed("logs-1"),
          from("FROM logs-* METADATA _index", "logs-*")
        ) shouldBe "FROM logs-1 METADATA _index | LIMIT 10"
      }
      "leave a bracketed METADATA clause in place" in {
        rewrite(
          "FROM logs-* [METADATA _index] | LIMIT 10",
          allowed("logs-1"),
          from("FROM logs-* [METADATA _index]", "logs-*")
        ) shouldBe "FROM logs-1 [METADATA _index] | LIMIT 10"
      }
      "recognize a lowercase source command" in {
        rewrite("from logs-* | limit 10", allowed("logs-1"), from("from logs-*", "logs-*")) shouldBe
          "from logs-1 | limit 10"
      }
      "replace the index list of a source command that follows a SET prelude" in {
        rewrite(
          "SET foo = 1; FROM logs-* | LIMIT 10",
          allowed("logs-1"),
          from("FROM logs-*", "logs-*")
        ) shouldBe "SET foo = 1; FROM logs-1 | LIMIT 10"
      }
      "not touch a column that happens to share the index name" in {
        rewrite(
          """FROM status | WHERE status == "ok"""",
          allowed("other"),
          from("FROM status", "status")
        ) shouldBe """FROM other | WHERE status == "ok""""
      }
      "not touch an index name mentioned inside a string literal" in {
        rewrite(
          """FROM status | EVAL x = "FROM status"""",
          allowed("other"),
          from("FROM status", "status")
        ) shouldBe """FROM other | EVAL x = "FROM status""""
      }
      "leave a METADATA clause a comment separates from the index list in place" in {
        rewrite(
          "FROM logs-*/* and */METADATA _index",
          allowed("logs-1"),
          from("FROM logs-*/* and */METADATA _index", "logs-*")
        ) shouldBe "FROM logs-1/* and */METADATA _index"
      }
      "handle an index actually named like the METADATA keyword" in {
        rewrite("FROM metadata | LIMIT 10", allowed("metadata"), from("FROM metadata", "metadata")) shouldBe
          "FROM metadata | LIMIT 10"
      }
      "handle an index whose name only ends with the METADATA keyword" in {
        rewrite(
          "FROM app-metadata* | LIMIT 10",
          allowed("app-metadata-1"),
          from("FROM app-metadata*", "app-metadata*")
        ) shouldBe "FROM app-metadata-1 | LIMIT 10"
      }
      "handle an index whose name holds the METADATA keyword after a dot or an asterisk" in {
        rewrite(
          "FROM .ds-metadata, logs*metadata | LIMIT 10",
          allowed("a"),
          from("FROM .ds-metadata, logs*metadata", ".ds-metadata,logs*metadata")
        ) shouldBe "FROM a | LIMIT 10"
      }
      "replace the index list of a TS command" in {
        rewrite("TS metrics-* | LIMIT 10", allowed("metrics-1"), from("TS metrics-*", "metrics-*")) shouldBe
          "TS metrics-1 | LIMIT 10"
      }
      "replace the index list of a source command written across more than one line" in {
        rewrite(
          "FROM logs-*\n| LIMIT 10",
          allowed("logs-1"),
          from("FROM logs-*", "logs-*")
        ) shouldBe "FROM logs-1\n| LIMIT 10"
      }
      "replace the index list of a source command that follows a line the query opens with" in {
        rewrite(
          "// leading comment\nFROM logs-* | LIMIT 10",
          allowed("logs-1"),
          from("FROM logs-*", "logs-*")
        ) shouldBe "// leading comment\nFROM logs-1 | LIMIT 10"
      }
    }
    "a source command holds a subquery" should {
      "replace the index list of a subquery, which ES reads as a source command of its own" in {
        rewrite(
          "FROM (FROM idx_a | LIMIT 1) | LIMIT 10",
          allowed("idx_b"),
          from("FROM (FROM idx_a | LIMIT 1)", ""),
          from("FROM idx_a", "idx_a")
        ) shouldBe "FROM (FROM idx_b | LIMIT 1) | LIMIT 10"
      }
      "give each subquery only what its own pattern matches, since neither may answer for the other" in {
        rewrite(
          "FROM (FROM a* | LIMIT 1), (FROM b* | LIMIT 1) | LIMIT 10",
          allowed("a1", "b1"),
          from("FROM (FROM a* | LIMIT 1), (FROM b* | LIMIT 1)", ""),
          from("FROM a*", "a*"),
          from("FROM b*", "b*")
        ) shouldBe "FROM (FROM a1 | LIMIT 1), (FROM b1 | LIMIT 1) | LIMIT 10"
      }
      "mask a subquery the ACL left nothing for without touching its siblings" in {
        rewrite(
          "FROM (FROM b | LIMIT 1), (FROM c | LIMIT 1) | LIMIT 10",
          allowed("c"),
          from("FROM (FROM b | LIMIT 1), (FROM c | LIMIT 1)", ""),
          from("FROM b", "b"),
          from("FROM c", "c")
        ) should fullyMatch regex
          s"FROM \\(FROM $maskedIndex \\| LIMIT 1\\), \\(FROM c \\| LIMIT 1\\) \\| LIMIT 10"
      }
      "replace every source command naming the same index, not only the first ES reported" in {
        rewrite(
          "FROM (FROM secret | LIMIT 1), (FROM secret | LIMIT 1) | LIMIT 10",
          allowed("allowed_idx"),
          from("FROM (FROM secret | LIMIT 1), (FROM secret | LIMIT 1)", ""),
          from("FROM secret", "secret"),
          from("FROM secret", "secret")
        ) should fullyMatch regex
          s"FROM \\(FROM $maskedIndex \\| LIMIT 1\\), \\(FROM $maskedIndex \\| LIMIT 1\\) \\| LIMIT 10"
      }
      "replace the index list of a subquery nested in another subquery" in {
        rewrite(
          "FROM (FROM (FROM a | LIMIT 1) | LIMIT 1) | LIMIT 10",
          allowed("a"),
          from("FROM (FROM (FROM a | LIMIT 1) | LIMIT 1)", ""),
          from("FROM (FROM a | LIMIT 1)", ""),
          from("FROM a", "a")
        ) shouldBe "FROM (FROM (FROM a | LIMIT 1) | LIMIT 1) | LIMIT 10"
      }
      "refuse to read a command mixing indices of its own with a subquery, which ES reports merged into one list" in {
        readingFailureFor(
          "FROM a, (FROM b | LIMIT 1), c | LIMIT 10",
          allowed("a"),
          from("FROM a, (FROM b | LIMIT 1), c", "a,c"),
          from("FROM b", "b")
        ) shouldBe SubqueryInSourceCommand("a,c")
      }
    }
    "LOOKUP JOIN is used" should {
      "leave an authorized target untouched" in {
        val query = "FROM src | LOOKUP JOIN lookup_idx ON key"
        rewrite(
          query,
          allowed("src", "lookup_idx"),
          from("FROM src", "src"),
          join("lookup_idx", "lookup_idx")
        ) shouldBe query
      }
      "mask an unauthorized target while leaving an authorized source command alone" in {
        rewrite(
          "FROM src | LOOKUP JOIN secret_idx ON key",
          allowed("src"),
          from("FROM src", "src"),
          join("secret_idx", "secret_idx")
        ) should fullyMatch regex s"FROM src \\| LOOKUP JOIN $maskedIndex ON key"
      }
      "handle a target whose name only ends with the ON keyword" in {
        rewrite(
          "FROM src | LOOKUP JOIN ref-on ON key",
          allowed("src", "ref-on"),
          from("FROM src", "src"),
          join("ref-on", "ref-on")
        ) shouldBe "FROM src | LOOKUP JOIN ref-on ON key"
      }
      "not rewrite a join key that shares the target's name" in {
        rewrite(
          "FROM src | LOOKUP JOIN prices ON prices",
          allowed("src"),
          from("FROM src", "src"),
          join("prices", "prices")
        ) should fullyMatch regex s"FROM src \\| LOOKUP JOIN $maskedIndex ON prices"
      }
      "keep a wildcard source command and a masked target from contaminating each other" in {
        rewrite(
          "FROM book* | LOOKUP JOIN book_prices ON isbn",
          allowed("bookstore"),
          from("FROM book*", "book*"),
          join("book_prices", "book_prices")
        ) should fullyMatch regex s"FROM bookstore \\| LOOKUP JOIN $maskedIndex ON isbn"
      }
      "include the target in a wildcard source command's list when the wildcard genuinely matches it" in {
        rewrite(
          "FROM book_* | LOOKUP JOIN book_prices ON isbn",
          allowed("book_catalog", "book_prices"),
          from("FROM book_*", "book_*"),
          join("book_prices", "book_prices")
        ) should fullyMatch regex
          "FROM (book_catalog,book_prices|book_prices,book_catalog) \\| LOOKUP JOIN book_prices ON isbn"
      }
      "exclude the target from a wildcard source command's list when the wildcard doesn't match it" in {
        rewrite(
          "FROM bookstore* | LOOKUP JOIN lookup_idx ON isbn",
          allowed("bookstore1", "lookup_idx"),
          from("FROM bookstore*", "bookstore*"),
          join("lookup_idx", "lookup_idx")
        ) shouldBe "FROM bookstore1 | LOOKUP JOIN lookup_idx ON isbn"
      }
      "not strip a literal shared with the source command from its own replacement" in {
        val query = "FROM shared_idx | LOOKUP JOIN shared_idx ON key"
        rewrite(
          query,
          allowed("shared_idx"),
          from("FROM shared_idx", "shared_idx"),
          join("shared_idx", "shared_idx")
        ) shouldBe query
      }
      "rewrite a join that opens a FORK branch" in {
        rewrite(
          "FROM src | FORK (LOOKUP JOIN secret_idx ON k) (WHERE x > 1)",
          allowed("src"),
          from("FROM src", "src"),
          join("secret_idx", "secret_idx")
        ) should fullyMatch regex s"FROM src \\| FORK \\(LOOKUP JOIN $maskedIndex ON k\\) \\(WHERE x > 1\\)"
      }
      "rewrite every join in a query with more than one" in {
        rewrite(
          "FROM src | LOOKUP JOIN allowed_idx ON k1 | LOOKUP JOIN secret_idx ON k2",
          allowed("src", "allowed_idx"),
          from("FROM src", "src"),
          join("allowed_idx", "allowed_idx"),
          join("secret_idx", "secret_idx")
        ) should fullyMatch regex
          s"FROM src \\| LOOKUP JOIN allowed_idx ON k1 \\| LOOKUP JOIN $maskedIndex ON k2"
      }
      "mask a source command the ACL left nothing for" in {
        rewrite(
          "FROM forbidden | LOOKUP JOIN lookup_idx ON key",
          allowed("lookup_idx"),
          from("FROM forbidden", "forbidden"),
          join("lookup_idx", "lookup_idx")
        ) should fullyMatch regex s"FROM $maskedIndex \\| LOOKUP JOIN lookup_idx ON key"
      }
      "refuse to read a target that is not a single plain index" in {
        readingFailureFor(
          """FROM src | LOOKUP JOIN "a,b" ON key""",
          allowed("src"),
          from("FROM src", "src"),
          join("\"a,b\"", "a,b")
        ) shouldBe UnsupportedIndexList("a,b")
      }
    }
    "PROMQL is used" should {
      "replace the index pattern named by its index parameter" in {
        rewrite(
          "PROMQL index=metrics-* step=1m rate(v)",
          allowed("metrics-1"),
          from("metrics-*", "metrics-*")
        ) shouldBe "PROMQL index=metrics-1 step=1m rate(v)"
      }
      "replace a quoted index parameter holding a comma separated list" in {
        rewrite("""PROMQL index="a,b" step=1m v""", allowed("b"), from(""""a,b"""", "a,b")) shouldBe
          "PROMQL index=b step=1m v"
      }
      "refuse to read a command that leaves ES to pick the indices" in {
        readingFailureFor(
          "PROMQL step=1m rate(v)",
          allowed("metrics-1"),
          from("PROMQL step=1m rate(v)", "*")
        ) shouldBe PromqlLeaningOnDefaultIndex
      }
    }
    "the rewritten query is held to what ES reads out of it" should {
      "accept a rewrite ES reads exactly as it was meant" in {
        verify(
          "FROM logs-* | LIMIT 10",
          allowed("logs-1"),
          esReads = List(EsqlIndexListRead(isLookupJoin = false, "logs-1")),
          from("FROM logs-*", "logs-*")
        ) shouldBe Right("FROM logs-1 | LIMIT 10")
      }
      "reject a rewrite that left an index the ACL did not allow behind" in {
        verify(
          "FROM a, // and\n b | LIMIT 10",
          allowed("b"),
          esReads = List(EsqlIndexListRead(isLookupJoin = false, "b,b")),
          from("FROM a, // and\n b", "a,b")
        ) shouldBe Left(EsqlQueryRejection.QueryNotReplacedAsIntended(List("b"), List("b,b")))
      }
      "reject a rewrite ES cannot parse at all, which it reads nothing out of" in {
        verify(
          "FROM logs-* | LIMIT 10",
          allowed("logs-1"),
          esReads = List.empty,
          from("FROM logs-*", "logs-*")
        ) shouldBe Left(EsqlQueryRejection.QueryNotReplacedAsIntended(List("logs-1"), List.empty))
      }
      "hold a LOOKUP JOIN target to being read as one" in {
        verify(
          "FROM src | LOOKUP JOIN lookup_idx ON key",
          allowed("src", "lookup_idx"),
          esReads = List(
            EsqlIndexListRead(isLookupJoin = false, "src"),
            EsqlIndexListRead(isLookupJoin = false, "lookup_idx")
          ),
          from("FROM src", "src"),
          join("lookup_idx", "lookup_idx")
        ) shouldBe Left(
          EsqlQueryRejection.QueryNotReplacedAsIntended(
            List("LOOKUP JOIN lookup_idx", "src"),
            List("lookup_idx", "src")
          )
        )
      }
    }
    "the query does not hold the index list ES reported" should {
      "refuse to read a list that is not written where ES reported it" in {
        readingFailureFor("FROM src | LIMIT 10", allowed("src"), at(99, "FROM src", "src")) shouldBe
          NotWhereEsReportedIt("src")
      }
    }
  }

  private def rewrite(
      query: String,
      allowed: NonEmptyList[RequestedIndex[ClusterIndexName]],
      reported: ReportedRelation*
  ): String = {
    val tables = tablesIn(query, reported)
      .fold(failure => fail(s"index lists were not read: ${failure.toString}"), identity)
    EsqlIndexListReplacing.queryWithAllowedIndices(EsqlQuery(query), tables, allowed).query.value
  }

  private def readingFailureFor(
      query: String,
      allowed: NonEmptyList[RequestedIndex[ClusterIndexName]],
      reported: ReportedRelation*
  ): EsqlIndexListReadingFailure = {
    tablesIn(query, reported).fold(
      identity,
      tables =>
        fail(
          s"index lists were read, although they should not have been: " +
            s"${EsqlIndexListReplacing.queryWithAllowedIndices(EsqlQuery(query), tables, allowed).query.value}"
        )
    )
  }

  private def verify(
      query: String,
      allowed: NonEmptyList[RequestedIndex[ClusterIndexName]],
      esReads: List[EsqlIndexListRead],
      reported: ReportedRelation*
  ): Either[EsqlQueryRejection, String] = {
    val tables = tablesIn(query, reported)
      .fold(failure => fail(s"index lists were not read: ${failure.toString}"), identity)
    val replaced = EsqlIndexListReplacing.queryWithAllowedIndices(EsqlQuery(query), tables, allowed)
    EsqlIndexListReplacing.verified(replaced, esReads).map(_.value)
  }

  private def tablesIn(
      query: String,
      reported: Seq[ReportedRelation]
  ): Either[EsqlIndexListReadingFailure, NonEmptyList[EsqlIndexTable]] = {
    val relations = reported.toList
      .foldLeft((Map.empty[String, Int], List.empty[EsqlReportedRelation])) {
        case ((claimedUpTo, relations), relation) =>
          val offset = relation.forcedOffset.getOrElse {
            query.indexOf(relation.writtenText, claimedUpTo.getOrElse(relation.writtenText, 0))
          }
          (claimedUpTo.updated(relation.writtenText, offset + 1), relations :+ relation.reportedAt(query, offset))
      }
      ._2
    EsqlIndexListLocator
      .indexTablesIn(EsqlQuery(query), relations)
      .map(tables => NonEmptyList.fromListUnsafe(tables))
  }

  private def from(writtenText: String, indexList: String): ReportedRelation =
    ReportedRelation(writtenText, indexList, isLookupJoin = false, forcedOffset = None)

  private def join(writtenText: String, indexList: String): ReportedRelation =
    ReportedRelation(writtenText, indexList, isLookupJoin = true, forcedOffset = None)

  private def at(offset: Int, writtenText: String, indexList: String): ReportedRelation =
    ReportedRelation(writtenText, indexList, isLookupJoin = false, forcedOffset = Some(offset))

  private def allowed(names: String*): NonEmptyList[RequestedIndex[ClusterIndexName]] =
    NonEmptyList.fromListUnsafe(names.toList.flatMap(RequestedIndex.fromString))

  /** Written the way ES's parser builds it: the raw text of the node, and where in the query that text sits. */
  private final case class ReportedRelation(
      writtenText: String,
      indexList: String,
      isLookupJoin: Boolean,
      forcedOffset: Option[Int]
  ) {

    def reportedAt(query: String, offset: Int): EsqlReportedRelation = {
      val before = query.take(offset)
      EsqlReportedRelation(
        indexList = indexList,
        writtenAt = EsqlSourceLocation(
          line = before.count(_ == '\n') + 1,
          column = offset - (before.lastIndexOf('\n') + 1)
        ),
        writtenText = writtenText,
        isLookupJoin = isLookupJoin
      )
    }

  }

}
