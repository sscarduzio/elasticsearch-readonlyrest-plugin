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
import tech.beshu.ror.es.esql.EsqlCommandKind.{LookupJoin, SourceCommand}
import tech.beshu.ror.es.esql.EsqlQueryNarrowing.{IndexListsMismatch, PromqlLeaningOnDefaultIndex}
import tech.beshu.ror.es.esql.{EsqlIndexTable, EsqlNarrowingFailure, EsqlQuery, EsqlQueryNarrowing, NormalizedIndexList}
import tech.beshu.ror.syntax.*

class EsqlQueryNarrowingTest extends AnyWordSpec {

  private val maskedIndex = """ROR_[A-Za-z0-9]{10}""".r

  "EsqlQueryNarrowing.narrowedQuery" when {
    "only FROM is used" should {
      "narrow a wildcard to the allowed indices" in {
        rewrite("FROM logs-* | LIMIT 10", tables("logs-*"), allowed("logs-1", "logs-2")) should
          fullyMatch regex "FROM (logs-1,logs-2|logs-2,logs-1) \\| LIMIT 10"
      }
      "keep the query as it was written apart from the index list" in {
        rewrite("FROM bookshop | LIMIT 100", tables("bookshop"), allowed("bookstore")) shouldBe
          "FROM bookstore | LIMIT 100"
      }
      "narrow a comma separated list written with spaces" in {
        rewrite("FROM a, b | LIMIT 10", tables("a,b"), allowed("a")) shouldBe "FROM a | LIMIT 10"
      }
      "narrow a comma separated list whose entries are quoted" in {
        rewrite("""FROM "a", b | LIMIT 10""", tables("a,b"), allowed("a")) shouldBe "FROM a | LIMIT 10"
      }
      "narrow a single quoted index list holding a comma separated list" in {
        rewrite("""FROM "a,b" | LIMIT 10""", tables("a,b"), allowed("b")) shouldBe "FROM b | LIMIT 10"
      }
      "leave a METADATA clause in place" in {
        rewrite("FROM logs-* METADATA _index | LIMIT 10", tables("logs-*"), allowed("logs-1")) shouldBe
          "FROM logs-1 METADATA _index | LIMIT 10"
      }
      "leave a bracketed METADATA clause in place" in {
        rewrite("FROM logs-* [METADATA _index] | LIMIT 10", tables("logs-*"), allowed("logs-1")) shouldBe
          "FROM logs-1 [METADATA _index] | LIMIT 10"
      }
      "recognize a lowercase source command" in {
        rewrite("from logs-* | limit 10", tables("logs-*"), allowed("logs-1")) shouldBe "from logs-1 | limit 10"
      }
      "narrow a source command that follows a SET prelude" in {
        rewrite("SET foo = 1; FROM logs-* | LIMIT 10", tables("logs-*"), allowed("logs-1")) shouldBe
          "SET foo = 1; FROM logs-1 | LIMIT 10"
      }
      "not treat a semicolon inside a string literal as a command boundary" in {
        rewrite("""FROM logs-* | EVAL x = "; FROM secret"""", tables("logs-*"), allowed("logs-1")) shouldBe
          """FROM logs-1 | EVAL x = "; FROM secret""""
      }
      "not touch a column that happens to share the index name" in {
        rewrite("""FROM status | WHERE status == "ok"""", tables("status"), allowed("other")) shouldBe
          """FROM other | WHERE status == "ok""""
      }
      "not touch an index name mentioned inside a string literal" in {
        rewrite("""FROM status | EVAL x = "FROM status"""", tables("status"), allowed("other")) shouldBe
          """FROM other | EVAL x = "FROM status""""
      }
      "not be confused by a comment mentioning a source command" in {
        rewrite("FROM logs-1 // FROM secret\n| LIMIT 10", tables("logs-1"), allowed("logs-1")) shouldBe
          "FROM logs-1 // FROM secret\n| LIMIT 10"
      }
      "handle an index actually named like the METADATA keyword" in {
        rewrite("FROM metadata | LIMIT 10", tables("metadata"), allowed("metadata")) shouldBe "FROM metadata | LIMIT 10"
      }
      "handle an index whose name only ends with the METADATA keyword" in {
        rewrite("FROM app-metadata* | LIMIT 10", tables("app-metadata*"), allowed("app-metadata-1")) shouldBe
          "FROM app-metadata-1 | LIMIT 10"
      }
      "handle an index whose name holds the METADATA keyword after a dot or an asterisk" in {
        rewrite(
          "FROM .ds-metadata, logs*metadata | LIMIT 10",
          tables(".ds-metadata,logs*metadata"),
          allowed("a")
        ) shouldBe
          "FROM a | LIMIT 10"
      }
      "leave a METADATA clause a comment separates from the index list in place" in {
        rewrite("FROM logs-*/* and */METADATA _index", tables("logs-*"), allowed("logs-1")) shouldBe
          "FROM logs-1/* and */METADATA _index"
      }
      "not mistake a parenthesized expression opening with a source command's name for a command" in {
        rewrite("""FROM logs-* | WHERE (ts > "2024-01-01") | LIMIT 10""", tables("logs-*"), allowed("logs-1")) shouldBe
          """FROM logs-1 | WHERE (ts > "2024-01-01") | LIMIT 10"""
      }
      "not mistake a function's first argument sharing a source command's name for a command" in {
        rewrite("FROM logs-* | EVAL b = CASE(ts > 1, 1, 0) | LIMIT 10", tables("logs-*"), allowed("logs-1")) shouldBe
          "FROM logs-1 | EVAL b = CASE(ts > 1, 1, 0) | LIMIT 10"
      }
      "narrow the index list of a TS command" in {
        rewrite("TS metrics-* | LIMIT 10", tables("metrics-*"), allowed("metrics-1")) shouldBe
          "TS metrics-1 | LIMIT 10"
      }
      "narrow the index list inside a FROM subquery" in {
        rewrite("FROM (FROM idx_a | LIMIT 1) | LIMIT 10", tables("idx_a"), allowed("idx_b")) shouldBe
          "FROM (FROM idx_b | LIMIT 1) | LIMIT 10"
      }
      "keep the separator when a subquery follows a plain index list" in {
        rewrite("FROM a, (FROM b | LIMIT 1) | LIMIT 10", tables("a", "b"), allowed("a", "b")) shouldBe
          "FROM a, (FROM b | LIMIT 1) | LIMIT 10"
      }
      "narrow each source command to its own pattern when the query has more than one" in {
        rewrite("FROM a*, (FROM b | LIMIT 1) | LIMIT 10", tables("a*", "b"), allowed("a1", "b")) shouldBe
          "FROM a1, (FROM b | LIMIT 1) | LIMIT 10"
      }
      "mask a source command the ACL left nothing for instead of handing it another one's indices" in {
        rewrite("FROM a, (FROM b | LIMIT 1) | LIMIT 10", tables("a", "b"), allowed("a")) should
          fullyMatch regex s"FROM a, \\(FROM $maskedIndex \\| LIMIT 1\\) \\| LIMIT 10"
      }
      "narrow an index list holding a block comment" in {
        rewrite("FROM a, /* and */ b | LIMIT 10", tables("a,b"), allowed("a")) shouldBe "FROM a | LIMIT 10"
      }
      "narrow an index list holding a line comment" in {
        rewrite("FROM a, // and\n b | LIMIT 10", tables("a,b"), allowed("b")) shouldBe "FROM b | LIMIT 10"
      }
      "narrow every entry of an index list that a subquery splits in two" in {
        rewrite("FROM a, (FROM b | LIMIT 1), c | LIMIT 10", tables("a,c", "b"), allowed("a", "b")) shouldBe
          "FROM a, (FROM b | LIMIT 1) | LIMIT 10"
      }
      "keep the separator when the index list opens with a subquery" in {
        val query = "FROM (FROM b | LIMIT 1), a | LIMIT 10"
        rewrite(query, tables("a", "b"), allowed("a", "b")) shouldBe query
      }
      "narrow every subquery of an index list, not only the first" in {
        rewrite(
          "FROM (FROM a* | LIMIT 1), (FROM b* | LIMIT 1) | LIMIT 10",
          tables("a*", "b*"),
          allowed("a1", "b1")
        ) shouldBe "FROM (FROM a1 | LIMIT 1), (FROM b1 | LIMIT 1) | LIMIT 10"
      }
      "hand the whole allowed set to twin subqueries ES reported as a single index list" in {
        rewrite(
          "FROM (FROM secret | LIMIT 1), (FROM secret | LIMIT 1) | LIMIT 10",
          tables("secret"),
          allowed("allowed_idx")
        ) shouldBe "FROM (FROM allowed_idx | LIMIT 1), (FROM allowed_idx | LIMIT 1) | LIMIT 10"
      }
      "mask twin subqueries ES reported as an index list each, since neither may answer for the other" in {
        rewrite(
          "FROM (FROM secret | LIMIT 1), (FROM secret | LIMIT 1) | LIMIT 10",
          tables("secret", "secret"),
          allowed("allowed_idx")
        ) should fullyMatch regex
          s"FROM \\(FROM $maskedIndex \\| LIMIT 1\\), \\(FROM $maskedIndex \\| LIMIT 1\\) \\| LIMIT 10"
      }
      "narrow the index list of a subquery nested in another subquery" in {
        val query = "FROM (FROM (FROM a | LIMIT 1), b | LIMIT 1), c | LIMIT 10"
        rewrite(query, tables("a", "b", "c"), allowed("a", "b", "c")) shouldBe query
      }
      "mask a subquery the ACL left nothing for without touching its siblings" in {
        rewrite(
          "FROM a, (FROM b | LIMIT 1), (FROM c | LIMIT 1) | LIMIT 10",
          tables("a", "b", "c"),
          allowed("a", "c")
        ) should fullyMatch regex
          s"FROM a, \\(FROM $maskedIndex \\| LIMIT 1\\), \\(FROM c \\| LIMIT 1\\) \\| LIMIT 10"
      }
      "mask the index list as nonexistent when nothing is left for it" in {
        rewrite(
          "FROM forbidden | LOOKUP JOIN lookup_idx ON key",
          tables("forbidden") ++ lookupTables("lookup_idx"),
          allowed("lookup_idx")
        ) should fullyMatch regex s"FROM $maskedIndex \\| LOOKUP JOIN lookup_idx ON key"
      }
    }
    "LOOKUP JOIN is used" should {
      "leave an authorized target untouched" in {
        val query = "FROM src | LOOKUP JOIN lookup_idx ON key"
        rewrite(query, tables("src") ++ lookupTables("lookup_idx"), allowed("src", "lookup_idx")) shouldBe query
      }
      "mask an unauthorized target while leaving an authorized FROM alone" in {
        rewrite(
          "FROM src | LOOKUP JOIN secret_idx ON key",
          tables("src") ++ lookupTables("secret_idx"),
          allowed("src")
        ) should fullyMatch regex s"FROM src \\| LOOKUP JOIN $maskedIndex ON key"
      }
      "handle a target whose name only ends with the ON keyword" in {
        rewrite(
          "FROM src | LOOKUP JOIN ref-on ON key",
          tables("src") ++ lookupTables("ref-on"),
          allowed("src", "ref-on")
        ) shouldBe "FROM src | LOOKUP JOIN ref-on ON key"
      }
      "not rewrite a join key that shares the target's name" in {
        rewrite(
          "FROM src | LOOKUP JOIN prices ON prices",
          tables("src") ++ lookupTables("prices"),
          allowed("src")
        ) should fullyMatch regex s"FROM src \\| LOOKUP JOIN $maskedIndex ON prices"
      }
      "keep a wildcard FROM and a masked target from contaminating each other" in {
        rewrite(
          "FROM book* | LOOKUP JOIN book_prices ON isbn",
          tables("book*") ++ lookupTables("book_prices"),
          allowed("bookstore")
        ) should fullyMatch regex s"FROM bookstore \\| LOOKUP JOIN $maskedIndex ON isbn"
      }
      "include the target in a wildcard FROM's list when the wildcard genuinely matches it" in {
        rewrite(
          "FROM book_* | LOOKUP JOIN book_prices ON isbn",
          tables("book_*") ++ lookupTables("book_prices"),
          allowed("book_catalog", "book_prices")
        ) should fullyMatch regex
          "FROM (book_catalog,book_prices|book_prices,book_catalog) \\| LOOKUP JOIN book_prices ON isbn"
      }
      "exclude the target from a wildcard FROM's list when the wildcard doesn't match it" in {
        rewrite(
          "FROM bookstore* | LOOKUP JOIN lookup_idx ON isbn",
          tables("bookstore*") ++ lookupTables("lookup_idx"),
          allowed("bookstore1", "lookup_idx")
        ) shouldBe "FROM bookstore1 | LOOKUP JOIN lookup_idx ON isbn"
      }
      "not strip a literal shared with FROM from FROM's own replacement" in {
        rewrite(
          "FROM shared_idx | LOOKUP JOIN shared_idx ON key",
          tables("shared_idx") ++ lookupTables("shared_idx"),
          allowed("shared_idx")
        ) shouldBe "FROM shared_idx | LOOKUP JOIN shared_idx ON key"
      }
      "rewrite a join that opens a FORK branch" in {
        rewrite(
          "FROM src | FORK (LOOKUP JOIN secret_idx ON k) (WHERE x > 1)",
          tables("src") ++ lookupTables("secret_idx"),
          allowed("src")
        ) should fullyMatch regex
          s"FROM src \\| FORK \\(LOOKUP JOIN $maskedIndex ON k\\) \\(WHERE x > 1\\)"
      }
      "rewrite every join in a query with more than one" in {
        rewrite(
          "FROM src | LOOKUP JOIN allowed_idx ON k1 | LOOKUP JOIN secret_idx ON k2",
          tables("src") ++ lookupTables("allowed_idx", "secret_idx"),
          allowed("src", "allowed_idx")
        ) should fullyMatch regex
          s"FROM src \\| LOOKUP JOIN allowed_idx ON k1 \\| LOOKUP JOIN $maskedIndex ON k2"
      }
    }
    "PROMQL is used" should {
      "narrow the index pattern its index parameter names" in {
        rewrite("PROMQL index=metrics-* step=1m rate(v)", tables("metrics-*"), allowed("metrics-1")) shouldBe
          "PROMQL index=metrics-1 step=1m rate(v)"
      }
      "narrow the index parameter wherever it sits among the other parameters" in {
        rewrite(
          "PROMQL step=1m index=metrics-* scrape_interval=30s v",
          tables("metrics-*"),
          allowed("metrics-1")
        ) shouldBe
          "PROMQL step=1m index=metrics-1 scrape_interval=30s v"
      }
      "narrow a quoted index parameter holding a comma separated list" in {
        rewrite("""PROMQL index="a,b" step=1m v""", tables("a,b"), allowed("b")) shouldBe
          "PROMQL index=b step=1m v"
      }
      "not mistake a comparison in the PromQL expression for a parameter" in {
        rewrite("PROMQL index=metrics-* step=1m v == 1", tables("metrics-*"), allowed("metrics-1")) shouldBe
          "PROMQL index=metrics-1 step=1m v == 1"
      }
      "not read an index parameter out of the PromQL expression's label matchers" in {
        narrowingFailureFor("""PROMQL step=1m v{index="secret"}""", tables("metrics-*"), allowed("a")) shouldBe
          PromqlLeaningOnDefaultIndex
      }
      "refuse to rewrite a command that leaves ES to pick the indices" in {
        narrowingFailureFor("PROMQL step=1m rate(v)", tables("metrics-*"), allowed("metrics-1")) shouldBe
          PromqlLeaningOnDefaultIndex
      }
      "narrow a PROMQL command the rest of the pipeline follows" in {
        rewrite("PROMQL index=metrics-* step=1m v | LIMIT 5", tables("metrics-*"), allowed("metrics-1")) shouldBe
          "PROMQL index=metrics-1 step=1m v | LIMIT 5"
      }
    }
    "the query cannot be matched to the tables ES reported" should {
      "refuse to rewrite when a reported table has no index list in the query" in {
        narrowingFailureFor("FROM src | LIMIT 10", tables("other_src"), allowed("src")) shouldBe
          IndexListsMismatch(SourceCommand, reported("other_src"), written("src"))
      }
      "refuse to rewrite when the query holds an index list ES didn't report" in {
        narrowingFailureFor(
          "FROM src | LOOKUP JOIN secret_idx ON key",
          tables("src"),
          allowed("src")
        ) shouldBe IndexListsMismatch(LookupJoin, reported(), written("secret_idx"))
      }
      "refuse to rewrite when a subquery holds an index list ES didn't report" in {
        narrowingFailureFor(
          "FROM a, (FROM secret | LIMIT 1) | LIMIT 10",
          tables("a"),
          allowed("a")
        ) shouldBe IndexListsMismatch(SourceCommand, reported(), written("secret"))
      }
      "refuse to rewrite a LOOKUP JOIN target that is not a single plain index" in {
        EsqlIndexTable.LookupJoin.parse(indexList("a,b")) shouldBe None
        EsqlIndexTable.LookupJoin.parse(indexList("-a")) shouldBe None
      }
    }
  }

  private def rewrite(
      query: String,
      tables: NonEmptyList[EsqlIndexTable],
      allowed: NonEmptyList[RequestedIndex[ClusterIndexName]]
  ): String = {
    EsqlQueryNarrowing
      .narrowedQuery(EsqlQuery(query), tables, allowed)
      .fold(mismatch => fail(s"query was not rewritten: $query; ${mismatch.toString}"), _.value)
  }

  private def narrowingFailureFor(
      query: String,
      tables: NonEmptyList[EsqlIndexTable],
      allowed: NonEmptyList[RequestedIndex[ClusterIndexName]]
  ): EsqlNarrowingFailure = {
    EsqlQueryNarrowing
      .narrowedQuery(EsqlQuery(query), tables, allowed)
      .fold(identity, narrowed => fail(s"query was rewritten, although it should not have been: ${narrowed.value}"))
  }

  private def tables(indexLists: String*): NonEmptyList[EsqlIndexTable] = {
    NonEmptyList.fromListUnsafe(
      indexLists.toList.map { list =>
        EsqlIndexTable.SourceCommand.parse(indexList(list)).getOrElse(fail(s"not a valid source command list: $list"))
      }
    )
  }

  private def lookupTables(indexLists: String*): List[EsqlIndexTable] = {
    indexLists.toList.map { list =>
      EsqlIndexTable.LookupJoin.parse(indexList(list)).getOrElse(fail(s"not a valid LOOKUP JOIN target: $list"))
    }
  }

  private def allowed(names: String*): NonEmptyList[RequestedIndex[ClusterIndexName]] = {
    NonEmptyList.fromListUnsafe(names.toList.flatMap(RequestedIndex.fromString))
  }

  private def reported(indexLists: String*): Set[NormalizedIndexList] = indexLists.toList.map(indexList).toCovariantSet

  private def written(indexLists: String*): Set[NormalizedIndexList] = indexLists.toList.map(indexList).toCovariantSet

  private def indexList(text: String): NormalizedIndexList =
    NormalizedIndexList.fromEsReport(text).getOrElse(fail(s"not an index list: $text"))

}
