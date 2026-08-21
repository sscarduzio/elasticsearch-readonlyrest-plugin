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
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, IndexName, RequestedIndex}
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

      EsqlIndexTable.newQueryFrom(query, replacements) shouldBe "FROM  logs-1,logs-2 | LIMIT 10"
    }

    "leave an authorized LOOKUP JOIN target untouched" in {
      val query = "FROM src | LOOKUP JOIN lookup_idx ON key"
      val replacements = NonEmptyList.of(
        replacementFor(newFromTable("src"), "src"),
        replacementFor(lookupJoinTable("lookup_idx"), "lookup_idx")
      )

      EsqlIndexTable.newQueryFrom(query, replacements) shouldBe "FROM   src | LOOKUP JOIN lookup_idx ON key"
    }

    "mask an unauthorized LOOKUP JOIN target" in {
      val query = "FROM src | LOOKUP JOIN secret_idx ON key"
      val replacements = NonEmptyList.of(
        replacementFor(newFromTable("src"), "src"),
        replacementFor(lookupJoinTable("secret_idx"), "ROR_nonexistent0001")
      )

      EsqlIndexTable.newQueryFrom(query, replacements) shouldBe
        "FROM   src | LOOKUP JOIN ROR_nonexistent0001 ON key"
    }

    "rewrite a wildcard-narrowed FROM and a masked LOOKUP JOIN independently, without cross-contaminating" +
      " each other's replacement text" in {
        val query = "FROM book* | LOOKUP JOIN book_prices ON isbn"
        val replacements = NonEmptyList.of(
          replacementFor(newFromTable("book*"), "bookstore1", "bookstore2"),
          replacementFor(lookupJoinTable("book_prices"), "ROR_nonexistent0002")
        )

        EsqlIndexTable.newQueryFrom(query, replacements) shouldBe
          "FROM   bookstore1,bookstore2 | LOOKUP JOIN ROR_nonexistent0002 ON isbn"
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

    "mask FROM to a nonexistent index when FROM is fully forbidden but LOOKUP JOIN is allowed " +
      "(masking FROM to a concrete nonexistent name still 400s the whole query in practice, but " +
      "buildReplacements' own contract is still to narrow independently per table)" in {
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

    "resolve a literal name shared by both a forbidden FROM and a forbidden LOOKUP JOIN to the identical " +
      "nonexistent index (newQueryFrom's per-table text replace can't tell the two occurrences apart " +
      "otherwise)" in {
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

  private def newFromTable(tableStringInQuery: String): EsqlIndexTable.From = {
    EsqlIndexTable.From(tableStringInQuery, NonEmptyList.one(indexNameFrom(tableStringInQuery)))
  }

  private def lookupJoinTable(tableStringInQuery: String): EsqlIndexTable.LookupJoin = {
    EsqlIndexTable.LookupJoin(tableStringInQuery, indexNameFrom(tableStringInQuery))
  }

  private def indexNameFrom(value: String): IndexName =
    IndexName.fromString(value).getOrElse(fail(s"not a valid index name: $value"))

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
