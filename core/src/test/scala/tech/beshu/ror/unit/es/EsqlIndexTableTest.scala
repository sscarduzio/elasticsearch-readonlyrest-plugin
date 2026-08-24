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
package tech.beshu.ror.unit.es

import cats.data.NonEmptyList
import cats.implicits.*
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestedIndex}
import tech.beshu.ror.es.EsqlIndexTable
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*

class EsqlIndexTableTest extends AnyWordSpec {

  "EsqlIndexTable.newQueryFrom" should {
    "rewrite a single FROM table" in {
      val query = "FROM logs-* | LIMIT 10"
      val replacements = NonEmptyList.one(
        replacementFor(newFromTable("logs-*"), "logs-1", "logs-2")
      )

      EsqlIndexTable.newQueryFrom(query, replacements) shouldBe Some("FROM logs-1,logs-2 | LIMIT 10")
    }

    "leave an authorized LOOKUP JOIN target untouched" in {
      val query = "FROM src | LOOKUP JOIN lookup_idx ON key"
      val replacements = NonEmptyList.of(
        replacementFor(newFromTable("src"), "src"),
        replacementFor(lookupJoinTable("lookup_idx"), "lookup_idx")
      )

      EsqlIndexTable.newQueryFrom(query, replacements) shouldBe Some("FROM src | LOOKUP JOIN lookup_idx ON key")
    }

    "mask an unauthorized LOOKUP JOIN target" in {
      val query = "FROM src | LOOKUP JOIN secret_idx ON key"
      val replacements = NonEmptyList.of(
        replacementFor(newFromTable("src"), "src"),
        replacementFor(lookupJoinTable("secret_idx"), "ROR_nonexistent0001")
      )

      EsqlIndexTable.newQueryFrom(query, replacements) shouldBe
        Some("FROM src | LOOKUP JOIN ROR_nonexistent0001 ON key")
    }

    "rewrite a wildcard-narrowed FROM and a masked LOOKUP JOIN independently, without cross-contaminating" +
      " each other's replacement text" in {
        val query = "FROM book* | LOOKUP JOIN book_prices ON isbn"
        val replacements = NonEmptyList.of(
          replacementFor(newFromTable("book*"), "bookstore1", "bookstore2"),
          replacementFor(lookupJoinTable("book_prices"), "ROR_nonexistent0002")
        )

        EsqlIndexTable.newQueryFrom(query, replacements) shouldBe
          Some("FROM bookstore1,bookstore2 | LOOKUP JOIN ROR_nonexistent0002 ON isbn")
      }

    // ES reports this list as `book_catalog,book_prices`, which no spelling below contains verbatim.
    "narrow a comma-separated FROM list, whatever way the user spelled it" when {
      "the entries are separated by a space" in {
        val query = "FROM book_catalog, book_prices | LIMIT 100"
        val replacements = NonEmptyList.one(
          replacementFor(newFromTable("book_catalog,book_prices"), "book_catalog")
        )

        EsqlIndexTable.newQueryFrom(query, replacements) shouldBe Some("FROM book_catalog | LIMIT 100")
      }
      "some of the entries are quoted" in {
        val query = """FROM "book_catalog", book_prices | LIMIT 100"""
        val replacements = NonEmptyList.one(
          replacementFor(newFromTable("book_catalog,book_prices"), "book_catalog")
        )

        EsqlIndexTable.newQueryFrom(query, replacements) shouldBe Some("FROM book_catalog | LIMIT 100")
      }
      "the whole list is one quoted string" in {
        val query = """FROM "book_catalog,book_prices" | LIMIT 100"""
        val replacements = NonEmptyList.one(
          replacementFor(newFromTable("book_catalog,book_prices"), "book_catalog")
        )

        EsqlIndexTable.newQueryFrom(query, replacements) shouldBe Some("FROM book_catalog | LIMIT 100")
      }
      "the entries are triple-quoted" in {
        val query = "FROM \"\"\"book_catalog\"\"\",\"\"\"book_prices\"\"\" | LIMIT 100"
        val replacements = NonEmptyList.one(
          replacementFor(newFromTable("book_catalog,book_prices"), "book_catalog")
        )

        EsqlIndexTable.newQueryFrom(query, replacements) shouldBe Some("FROM book_catalog | LIMIT 100")
      }
      "the source command is lowercase" in {
        val query = "from book_catalog, book_prices | limit 100"
        val replacements = NonEmptyList.one(
          replacementFor(newFromTable("book_catalog,book_prices"), "book_catalog")
        )

        EsqlIndexTable.newQueryFrom(query, replacements) shouldBe Some("from book_catalog | limit 100")
      }
    }

    "rewrite only the index list, not identifiers that happen to share an index's name" when {
      "a column has the same name as the FROM index" in {
        val query = """FROM status | WHERE status == "ok" | LIMIT 10"""
        val replacements = NonEmptyList.one(replacementFor(newFromTable("status"), "other"))

        EsqlIndexTable.newQueryFrom(query, replacements) shouldBe
          Some("""FROM other | WHERE status == "ok" | LIMIT 10""")
      }
      "the LOOKUP JOIN key has the same name as its target index" in {
        val query = "FROM src | LOOKUP JOIN prices ON prices"
        val replacements = NonEmptyList.of(
          replacementFor(newFromTable("src"), "src"),
          replacementFor(lookupJoinTable("prices"), "ROR_nonexistent0003")
        )

        EsqlIndexTable.newQueryFrom(query, replacements) shouldBe
          Some("FROM src | LOOKUP JOIN ROR_nonexistent0003 ON prices")
      }
      "an index name also appears inside a string literal" in {
        val query = """FROM logs | WHERE msg == "read from logs" | LIMIT 10"""
        val replacements = NonEmptyList.one(replacementFor(newFromTable("logs"), "logs-1"))

        EsqlIndexTable.newQueryFrom(query, replacements) shouldBe
          Some("""FROM logs-1 | WHERE msg == "read from logs" | LIMIT 10""")
      }
      "a METADATA clause follows the index list" in {
        val query = "FROM logs-* METADATA _index | LIMIT 10"
        val replacements = NonEmptyList.one(replacementFor(newFromTable("logs-*"), "logs-1"))

        EsqlIndexTable.newQueryFrom(query, replacements) shouldBe Some("FROM logs-1 METADATA _index | LIMIT 10")
      }
      "the index is itself named 'metadata'" in {
        val query = "FROM metadata | LIMIT 10"
        val replacements = NonEmptyList.one(replacementFor(newFromTable("metadata"), "other"))

        EsqlIndexTable.newQueryFrom(query, replacements) shouldBe Some("FROM other | LIMIT 10")
      }
      "an index name is a prefix of another identifier in the query" in {
        val query = "FROM book | EVAL x = book_prices | LIMIT 10"
        val replacements = NonEmptyList.one(replacementFor(newFromTable("book"), "book-1"))

        EsqlIndexTable.newQueryFrom(query, replacements) shouldBe Some("FROM book-1 | EVAL x = book_prices | LIMIT 10")
      }
    }

    "fail closed" when {
      "a table ES reported has no index list in the query" in {
        val query = "FROM logs | LIMIT 10"
        val replacements = NonEmptyList.of(
          replacementFor(newFromTable("logs"), "logs"),
          replacementFor(lookupJoinTable("prices"), "ROR_nonexistent0004")
        )

        EsqlIndexTable.newQueryFrom(query, replacements) shouldBe None
      }
      "a FROM table is only spelled as a LOOKUP JOIN target in the query" in {
        val query = "FROM src | LOOKUP JOIN prices ON key"
        val replacements = NonEmptyList.one(replacementFor(newFromTable("prices"), "other"))

        EsqlIndexTable.newQueryFrom(query, replacements) shouldBe None
      }
      "the same index list can be located in more than one place" in {
        val query = "FROM logs | LOOKUP JOIN prices ON key | FORK (WHERE a) (FROM logs)"
        val replacements = NonEmptyList.of(
          replacementFor(newFromTable("logs"), "logs-1"),
          replacementFor(lookupJoinTable("prices"), "prices")
        )

        EsqlIndexTable.newQueryFrom(query, replacements) shouldBe None
      }
      "the query spells the list in a way that doesn't normalize to what ES reported" in {
        val query = "FROM book_catalog, /* and */ book_prices | LIMIT 100"
        val replacements = NonEmptyList.one(
          replacementFor(newFromTable("book_catalog,book_prices"), "book_catalog")
        )

        EsqlIndexTable.newQueryFrom(query, replacements) shouldBe None
      }
    }
  }

  "EsqlIndexTable.buildReplacements" should {
    "keep both FROM and an authorized LOOKUP JOIN target unchanged" in {
      val fromTable = newFromTable("src")
      val lookupTable = lookupJoinTable("lookup_idx")

      val replacements = EsqlIndexTable.buildReplacements(
        NonEmptyList.of(fromTable, lookupTable),
        allowedIndices = authorizedIndicesOf("src", "lookup_idx")
      )

      newIndicesOf(replacements, fromTable) shouldBe NonEmptyList.one("src")
      newIndicesOf(replacements, lookupTable) shouldBe NonEmptyList.one("lookup_idx")
    }

    "mask an unauthorized LOOKUP JOIN target while leaving FROM's own narrowing untouched" in {
      val fromTable = newFromTable("src")
      val lookupTable = lookupJoinTable("secret_idx")

      val replacements = EsqlIndexTable.buildReplacements(
        NonEmptyList.of(fromTable, lookupTable),
        allowedIndices = authorizedIndicesOf("src")
      )

      newIndicesOf(replacements, fromTable) shouldBe NonEmptyList.one("src")
      assertMasked(newIndicesOf(replacements, lookupTable))
    }

    "mask FROM to a nonexistent index when FROM is fully forbidden but LOOKUP JOIN is allowed" in {
      val fromTable = newFromTable("forbidden_src")
      val lookupTable = lookupJoinTable("lookup_idx")

      val replacements = EsqlIndexTable.buildReplacements(
        NonEmptyList.of(fromTable, lookupTable),
        allowedIndices = authorizedIndicesOf("lookup_idx")
      )

      assertMasked(newIndicesOf(replacements, fromTable))
      newIndicesOf(replacements, lookupTable) shouldBe NonEmptyList.one("lookup_idx")
    }

    "not strip a literal index name from FROM's replacement just because the same name is also " +
      "requested by LOOKUP JOIN" in {
        val fromTable = newFromTable("shared_idx")
        val lookupTable = lookupJoinTable("shared_idx")

        val replacements = EsqlIndexTable.buildReplacements(
          NonEmptyList.of(fromTable, lookupTable),
          allowedIndices = authorizedIndicesOf("shared_idx")
        )

        newIndicesOf(replacements, fromTable) shouldBe NonEmptyList.one("shared_idx")
        newIndicesOf(replacements, lookupTable) shouldBe NonEmptyList.one("shared_idx")
      }

    "resolve a literal name shared by a forbidden FROM and a forbidden LOOKUP JOIN to the same " +
      "nonexistent index" in {
        val fromTable = newFromTable("shared_idx")
        val lookupTable = lookupJoinTable("shared_idx")
        // Keeps authorizedIndices non-empty (NonEmptyList can't be) while granting nothing to shared_idx.
        val decoyLookupTable = lookupJoinTable("decoy_lookup_only")

        val replacements = EsqlIndexTable.buildReplacements(
          NonEmptyList.of(fromTable, lookupTable, decoyLookupTable),
          allowedIndices = authorizedIndicesOf("decoy_lookup_only")
        )

        val fromMasked = newIndicesOf(replacements, fromTable)
        val lookupMasked = newIndicesOf(replacements, lookupTable)
        assertMasked(fromMasked)
        assertMasked(lookupMasked)
        fromMasked shouldBe lookupMasked
      }

    "include a LOOKUP JOIN target's name in a wildcard FROM's narrowed list when the wildcard genuinely " +
      "matches it too" in {
        val fromTable = newFromTable("book*")
        val lookupTable = lookupJoinTable("book_prices")

        val replacements = EsqlIndexTable.buildReplacements(
          NonEmptyList.of(fromTable, lookupTable),
          allowedIndices = authorizedIndicesOf("bookstore1", "bookstore2", "book_prices")
        )

        newIndicesOf(replacements, fromTable).toList.sorted shouldBe List("book_prices", "bookstore1", "bookstore2")
        newIndicesOf(replacements, lookupTable) shouldBe NonEmptyList.one("book_prices")
      }

    "exclude a LOOKUP JOIN target from a wildcard FROM's narrowed list when the wildcard doesn't match it" in {
      val fromTable = newFromTable("bookstore*")
      val lookupTable = lookupJoinTable("lookup_idx")

      val replacements = EsqlIndexTable.buildReplacements(
        NonEmptyList.of(fromTable, lookupTable),
        allowedIndices = authorizedIndicesOf("bookstore1", "bookstore2", "lookup_idx")
      )

      newIndicesOf(replacements, fromTable).toList.sorted shouldBe List("bookstore1", "bookstore2")
      newIndicesOf(replacements, lookupTable) shouldBe NonEmptyList.one("lookup_idx")
    }

    "resolve a literal FROM alias whose ACL-narrowed concrete index name doesn't textually match the " +
      "query's own alias text (e.g. the ACL resolved \"bookshop\" to its underlying \"bookstore\")" in {
        val fromTable = newFromTable("bookshop")

        val replacements = EsqlIndexTable.buildReplacements(
          NonEmptyList.one(fromTable),
          allowedIndices = authorizedIndicesOf("bookstore")
        )

        newIndicesOf(replacements, fromTable) shouldBe NonEmptyList.one("bookstore")
      }
  }

  "EsqlIndexTable.LookupJoin.parse" should {
    "accept a concrete local index name" in {
      EsqlIndexTable.LookupJoin.parse("book_prices").map(_.index.name.value) shouldBe Some("book_prices")
    }
    "reject a wildcard target" in {
      EsqlIndexTable.LookupJoin.parse("book_*") shouldBe None
    }
    "reject a remote cluster target" in {
      EsqlIndexTable.LookupJoin.parse("remote:book_prices") shouldBe None
    }
  }

  private def newFromTable(tableStringInQuery: String): EsqlIndexTable.From = {
    EsqlIndexTable.From.parse(tableStringInQuery).getOrElse(fail(s"not a valid FROM table: $tableStringInQuery"))
  }

  private def lookupJoinTable(tableStringInQuery: String): EsqlIndexTable.LookupJoin = {
    EsqlIndexTable.LookupJoin
      .parse(tableStringInQuery)
      .getOrElse(fail(s"not a valid LOOKUP JOIN table: $tableStringInQuery"))
  }

  private def indexNameFrom(value: String): ClusterIndexName =
    ClusterIndexName.fromString(value).getOrElse(fail(s"not a valid index name: $value"))

  private def authorizedIndicesOf(names: String*): NonEmptyList[RequestedIndex[ClusterIndexName]] = {
    NonEmptyList.fromListUnsafe(names.toList.flatMap(RequestedIndex.fromString))
  }

  private def replacementFor(table: EsqlIndexTable, newIndices: String*): EsqlIndexTable.Replacement = {
    EsqlIndexTable.Replacement(table, NonEmptyList.fromListUnsafe(newIndices.toList.map(indexNameFrom)))
  }

  private def newIndicesOf(
      replacements: NonEmptyList[EsqlIndexTable.Replacement],
      table: EsqlIndexTable
  ): NonEmptyList[String] = {
    replacements.find(_.table == table).getOrElse(fail(s"no replacement found for $table")).newIndices.map(_.show)
  }

  private def assertMasked(newIndices: NonEmptyList[String]): Unit = {
    newIndices.toList match {
      case single :: Nil => single should startWith("ROR_")
      case other         => fail(s"expected a single masked index, got $other")
    }
  }

}
