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
package tech.beshu.ror.integration.suites

import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyWordSpecLike, SingletonPluginTestSupport}
import tech.beshu.ror.utils.TestUjson.ujson
import tech.beshu.ror.utils.TestUjson.ujson.{Null, Num, Str}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, EsqlApiManager, IndexManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.{CustomScalaTestMatchers, Version}

/** Own cluster, not the shared xpack one: `book_*` is caught by the `*` its wildcard cases assert on. */
class EsqlLookupJoinSuite
    extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport
    with ESVersionSupportForAnyWordSpecLike
    with CustomScalaTestMatchers {

  override implicit val rorSettingsFileName: String = "/esql_lookup_join/readonlyrest.yml"

  override val nodeDataInitializer = Some { (esVersion: String, adminRestClient: RestClient) =>
    if (Version.greaterOrEqualThan(esVersion, 8, 11, 0)) {
      val documentManager = new DocumentManager(adminRestClient, esVersion)
      val indexManager = new IndexManager(adminRestClient, esVersion)

      documentManager.createDocAndAssert(
        index = "book_catalog",
        `type` = "_doc",
        id = 1,
        content = ujson.read("""{"book_id": 1, "title": "Leviathan Wakes"}""")
      )
      documentManager.createDocAndAssert(
        index = "book_catalog",
        `type` = "_doc",
        id = 2,
        content = ujson.read("""{"book_id": 2, "title": "Hyperion"}""")
      )

      if (Version.greaterOrEqualThan(esVersion, 8, 18, 0)) {
        indexManager
          .createIndex("book_prices", settings = Some(ujson.read("""{"settings": {"index": {"mode": "lookup"}}}""")))
          .force()
        indexManager
          .createIndex("store_ratings", settings = Some(ujson.read("""{"settings": {"index": {"mode": "lookup"}}}""")))
          .force()
      }
      documentManager.createDocAndAssert(
        index = "book_prices",
        `type` = "_doc",
        id = 1,
        content = ujson.read("""{"book_id": 1, "discount_price": 90}""")
      )
      documentManager.createDocAndAssert(
        index = "book_prices",
        `type` = "_doc",
        id = 2,
        content = ujson.read("""{"book_id": 2, "discount_price": 180}""")
      )
      documentManager.createDocAndAssert(
        index = "store_ratings",
        `type` = "_doc",
        id = 1,
        content = ujson.read("""{"book_id": 1, "rating": 4}""")
      )
      documentManager.createDocAndAssert(
        index = "store_ratings",
        `type` = "_doc",
        id = 2,
        content = ujson.read("""{"book_id": 2, "rating": 5}""")
      )
    }
  }

  private lazy val catalogOnlyEsqlManager = new EsqlApiManager(basicAuthClient("catalog", "test"), esVersionUsed)
  private lazy val pricesOnlyEsqlManager = new EsqlApiManager(basicAuthClient("prices", "test"), esVersionUsed)
  private lazy val bothIndicesEsqlManager = new EsqlApiManager(basicAuthClient("both", "test"), esVersionUsed)
  private lazy val bookWildcardEsqlManager = new EsqlApiManager(basicAuthClient("books", "test"), esVersionUsed)
  private lazy val allJoinTargetsEsqlManager = new EsqlApiManager(basicAuthClient("joins", "test"), esVersionUsed)
  private lazy val adminEsqlManager = new EsqlApiManager(basicAuthClient("admin", "container"), esVersionUsed)

  "An ESQL LOOKUP JOIN request" should {
    "be allowed" when {
      "both the FROM and the LOOKUP JOIN target are authorized" excludeES (
        allEs6x,
        allEs7x,
        allEs8xBelowEs818x
      ) in {
        val result = bothIndicesEsqlManager.execute(
          """FROM book_catalog | LOOKUP JOIN book_prices ON book_id | SORT book_id | LIMIT 100"""
        )

        result should have statusCode 200
        result.columnNames should contain only ("book_id", "title", "title.keyword", "discount_price")
        result.column("discount_price").toList should contain only (Num(90), Num(180))
      }
      "the FROM wildcard matches the LOOKUP JOIN target too, so its rows legitimately belong to FROM" excludeES (
        allEs6x,
        allEs7x,
        allEs8xBelowEs818x
      ) in {
        val result = bookWildcardEsqlManager.execute(
          """FROM book_* | LOOKUP JOIN book_prices ON book_id | SORT book_id | LIMIT 100"""
        )

        result should have statusCode 200
        result.columnNames should contain only ("book_id", "title", "title.keyword", "discount_price")
        result.column("title").toList should contain only (Str("Leviathan Wakes"), Str("Hyperion"), Null)
        result.rows.size should be(4)
      }
      "every LOOKUP JOIN target of a query with several is authorized on its own" excludeES (
        allEs6x,
        allEs7x,
        allEs8xBelowEs818x
      ) in {
        val result = allJoinTargetsEsqlManager.execute(
          """FROM book_c* | LOOKUP JOIN book_prices ON book_id | LOOKUP JOIN store_ratings ON book_id | SORT book_id | LIMIT 100"""
        )

        result should have statusCode 200
        result.columnNames should contain allOf ("discount_price", "rating")
        result.column("rating").toList should contain only (Num(4), Num(5))
        result.rows.size should be(2)
      }
    }
    "be rejected with a generic 'Unknown index' error, leaking neither the index's existence nor its data" when {
      "the LOOKUP JOIN target is not authorized, even though FROM's own target is fine" excludeES (
        allEs6x,
        allEs7x,
        allEs8xBelowEs818x
      ) in {
        val result = catalogOnlyEsqlManager.execute(
          """FROM book_catalog | LOOKUP JOIN book_prices ON book_id | LIMIT 100"""
        )

        result should have statusCode 400
        val reason = result.responseJson("error").obj("reason").str
        reason should include("Unknown index")
        reason should not include "book_prices"
      }
      "one LOOKUP JOIN target among several is not authorized" excludeES (
        allEs6x,
        allEs7x,
        allEs8xBelowEs818x
      ) in {
        val result = bothIndicesEsqlManager.execute(
          """FROM book_catalog | LOOKUP JOIN book_prices ON book_id | LOOKUP JOIN store_ratings ON book_id | LIMIT 100"""
        )

        result should have statusCode 400
        val reason = result.responseJson("error").obj("reason").str
        reason should include("Unknown index")
        reason should not include "store_ratings"
      }
      "the same index is named by both FROM and a LOOKUP JOIN, and only one of the two is authorized" excludeES (
        allEs6x,
        allEs7x,
        allEs8xBelowEs818x
      ) in {
        val result = catalogOnlyEsqlManager.execute(
          """FROM book_catalog, book_prices | LOOKUP JOIN book_prices ON book_id | LIMIT 100"""
        )

        result should have statusCode 400
        val reason = result.responseJson("error").obj("reason").str
        reason should include("Unknown index")
        reason should not include "book_prices"
      }
      "FROM's own target is not authorized, even though the LOOKUP JOIN target is fine" excludeES (
        allEs6x,
        allEs7x,
        allEs8xBelowEs818x
      ) in {
        val result = pricesOnlyEsqlManager.execute(
          """FROM book_catalog | LOOKUP JOIN book_prices ON book_id | LIMIT 100"""
        )

        result should have statusCode 400
        val reason = result.responseJson("error").obj("reason").str
        reason should include("Unknown index")
        reason should not include "book_catalog"
      }
    }
    "be rejected as forbidden" when {
      "ROR cannot read the LOOKUP JOIN target as a single index, so it cannot replace it" excludeES (
        allEs6x,
        allEs7x,
        allEs8xBelowEs818x
      ) in {
        val result = bothIndicesEsqlManager.execute(
          """FROM book_catalog | LOOKUP JOIN \"book_catalog,book_prices\" ON book_id | LIMIT 100"""
        )

        result should have statusCode 403
      }
    }
    "be left to run as written" when {
      "ROR cannot read the LOOKUP JOIN target, but the ACL narrowed nothing to hold the query to" excludeES (
        allEs6x,
        allEs7x,
        allEs8xBelowEs818x
      ) in {
        val result = adminEsqlManager.execute(
          """FROM book_catalog | LOOKUP JOIN \"book_catalog,book_prices\" ON book_id | LIMIT 100"""
        )

        result.responseCode should not be 403
      }
    }
    "replace an index list whose written form differs from the one ES's parser reports" when {
      "it is written with spaces after the commas" excludeES (allEs6x, allEs7x, allEs8xBelowEs811x) in {
        val result = catalogOnlyEsqlManager.execute("""FROM book_catalog, book_prices | LIMIT 100""")

        result should have statusCode 200
        result.columnNames should contain only ("book_id", "title", "title.keyword")
        result.rows.size should be(2)
      }
      "it carries the bracketed METADATA clause ES 8.x still accepts" excludeES (
        allEs6x,
        allEs7x,
        allEs8xBelowEs818x,
        allEs9x
      ) in {
        val result = catalogOnlyEsqlManager.execute("""FROM book_catalog, book_prices [METADATA _index] | LIMIT 100""")

        result should have statusCode 200
        result.columnNames should contain only ("book_id", "title", "title.keyword", "_index")
        result.rows.size should be(2)
      }
      "a comment holding a bracket interrupts it" excludeES (allEs6x, allEs7x, allEs8xBelowEs818x) in {
        val result = catalogOnlyEsqlManager.execute(
          """FROM book_catalog, /* and (also) */ book_prices | LIMIT 100"""
        )

        result should have statusCode 200
        result.columnNames should contain only ("book_id", "title", "title.keyword")
        result.rows.size should be(2)
      }
      "its entries are quoted" excludeES (allEs6x, allEs7x, allEs8xBelowEs818x) in {
        val result = catalogOnlyEsqlManager.execute("""FROM \"book_catalog\",\"book_prices\" | LIMIT 100""")

        result should have statusCode 200
        result.columnNames should contain only ("book_id", "title", "title.keyword")
        result.rows.size should be(2)
      }
    }
  }

}
