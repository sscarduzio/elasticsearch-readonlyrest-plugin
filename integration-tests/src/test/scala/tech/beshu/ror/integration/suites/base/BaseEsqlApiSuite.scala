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
package tech.beshu.ror.integration.suites.base

import org.scalatest.wordspec.AnyWordSpecLike
import tech.beshu.ror.integration.suites.base.BaseEsqlApiSuite.nodeDataInitializer
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyWordSpecLike, PluginTestSupport}
import tech.beshu.ror.utils.TestUjson.ujson
import tech.beshu.ror.utils.TestUjson.ujson.{Null, Num, Str}
import tech.beshu.ror.utils.containers.*
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, EsqlApiManager, IndexManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.{CustomScalaTestMatchers, Version}

trait BaseEsqlApiSuite
    extends AnyWordSpecLike
    with BaseEsClusterIntegrationTest
    with PluginTestSupport
    with SingleClientSupport
    with ESVersionSupportForAnyWordSpecLike
    with CustomScalaTestMatchers {

  override lazy val targetEs: EsContainer = container.nodes.head

  protected def rorClusterSecurityType: SecurityType

  override lazy val clusterContainer: EsClusterContainer = createLocalClusterContainer(
    EsClusterSettings.create(
      clusterName = "ROR1",
      nodeDataInitializer = nodeDataInitializer(),
      securityType = rorClusterSecurityType
    )
  )

  private lazy val adminEsqlManager = new EsqlApiManager(basicAuthClient("sqladmin", "pass"), esVersionUsed)
  private lazy val dev1EsqlManager = new EsqlApiManager(basicAuthClient("dev1sql", "test"), esVersionUsed)
  private lazy val dev2EsqlManager = new EsqlApiManager(basicAuthClient("dev2sql", "test"), esVersionUsed)
  private lazy val dev3EsqlManager = new EsqlApiManager(basicAuthClient("dev3sql", "test"), esVersionUsed)
  private lazy val catalogOnlyEsqlManager = new EsqlApiManager(basicAuthClient("catalog", "test"), esVersionUsed)
  private lazy val pricesOnlyEsqlManager = new EsqlApiManager(basicAuthClient("prices", "test"), esVersionUsed)
  private lazy val bothIndicesEsqlManager = new EsqlApiManager(basicAuthClient("both", "test"), esVersionUsed)
  private lazy val bookWildcardEsqlManager = new EsqlApiManager(basicAuthClient("books", "test"), esVersionUsed)
  private lazy val allJoinTargetsEsqlManager = new EsqlApiManager(basicAuthClient("joins", "test"), esVersionUsed)
  private lazy val unrestrictedEsqlManager = new EsqlApiManager(basicAuthClient("admin", "container"), esVersionUsed)

  "ESQL query request" when {
    "a source command is used" should {
      "be allowed" when {
        "user has no indices rule (has access to any index)" when {
          "full index name is used" excludeES (allEs6x, allEs7x, allEs8xBelowEs811x) in {
            val result = adminEsqlManager.execute("""FROM library | LIMIT 100""")
            result should have statusCode 200
            result.columnNames should contain only (
              "author",
              "author.keyword",
              "internal_id",
              "name",
              "name.keyword",
              "release_date"
            )
            result.column("author").toList should contain only (Str("James S.A. Corey"), Str("Dan Simmons"))
            result.column("internal_id").toList should contain only (Num(1), Num(2))
            result.rows.size should be(2)
          }
          "full indices names are used" excludeES (allEs6x, allEs7x, allEs8xBelowEs811x) in {
            val result = adminEsqlManager.execute("""FROM bookstore,library | LIMIT 100""")
            result should have statusCode 200
            result.columnNames should contain only (
              "author",
              "author.keyword",
              "internal_id",
              "name",
              "name.keyword",
              "release_date",
              "price"
            )
            result.column("author").toList should contain only (
              Str("James S.A. Corey"),
              Str("Dan Simmons"),
              Str("Frank Herbert")
            )
            result.column("internal_id").toList should contain only (Num(1), Num(2), Null)
            result.rows.size should be(5)
          }
          "wildcard is used" excludeES (allEs6x, allEs7x, allEs8xBelowEs811x) in {
            val result = adminEsqlManager.execute("""FROM * | LIMIT 100""")
            result should have statusCode 200
            result.columnNames should contain only (
              "author",
              "author.keyword",
              "internal_id",
              "name",
              "name.keyword",
              "release_date",
              "price"
            )
            result.column("author").toList should contain only (
              Str("James S.A. Corey"),
              Str("Dan Simmons"),
              Str("Frank Herbert")
            )
            result.column("internal_id").toList should contain only (Num(1), Num(2), Null)
            result.rows.size should be(5)
          }
          "alias is used" excludeES (allEs6x, allEs7x, allEs8xBelowEs811x) in {
            val result = adminEsqlManager.execute("""FROM bookshop | LIMIT 100""")
            result should have statusCode 200
            result.columnNames should contain only (
              "author",
              "author.keyword",
              "name",
              "name.keyword",
              "price",
              "release_date"
            )
            result.column("author").toList should contain only (
              Str("James S.A. Corey"),
              Str("Dan Simmons"),
              Str("Frank Herbert")
            )
            result.column("price").toList should contain only (Num(100), Num(200), Num(50))
            result.rows.size should be(3)
          }
        }
        "user has access to given index" when {
          "full index name is used" excludeES (allEs6x, allEs7x, allEs8xBelowEs811x) in {
            val result = dev1EsqlManager.execute("""FROM bookstore | LIMIT 100""")
            result should have statusCode 200
            result.columnNames should contain only ("author", "author.keyword", "name", "name.keyword", "release_date")
            result.column("author").toList should contain only (
              Str("James S.A. Corey"),
              Str("Dan Simmons"),
              Str("Frank Herbert")
            )
            result.rows.size should be(3)
          }
          "full indices names are used and one of them is not allowed" excludeES (
            allEs6x,
            allEs7x,
            allEs8xBelowEs811x
          ) in {
            val result = dev1EsqlManager.execute("""FROM bookstore,library | LIMIT 100""")
            result should have statusCode 200
            result.columnNames should contain only ("author", "author.keyword", "name", "name.keyword", "release_date")
            result.column("author").toList should contain only (
              Str("James S.A. Corey"),
              Str("Dan Simmons"),
              Str("Frank Herbert")
            )
            result.rows.size should be(3)
          }
          "full indices names are used, one of them is not allowed and they are separated by a space" excludeES (
            allEs6x,
            allEs7x,
            allEs8xBelowEs811x
          ) in {
            val result = dev1EsqlManager.execute("""FROM bookstore, library | LIMIT 100""")
            result should have statusCode 200
            result.columnNames should contain only ("author", "author.keyword", "name", "name.keyword", "release_date")
            result.column("author").toList should contain only (
              Str("James S.A. Corey"),
              Str("Dan Simmons"),
              Str("Frank Herbert")
            )
            result.rows.size should be(3)
          }
          "wildcard is used" excludeES (allEs6x, allEs7x, allEs8xBelowEs811x) in {
            val result = dev1EsqlManager.execute("""FROM book* | LIMIT 100""")
            result should have statusCode 200
            result.columnNames should contain only ("author", "author.keyword", "name", "name.keyword", "release_date")
            result.column("author").toList should contain only (
              Str("James S.A. Corey"),
              Str("Dan Simmons"),
              Str("Frank Herbert")
            )
            result.rows.size should be(3)
          }
          "alias is used" excludeES (allEs6x, allEs7x, allEs8xBelowEs811x) in {
            val result = dev1EsqlManager.execute("""FROM bookshop | LIMIT 100""")
            result should have statusCode 200
            result.columnNames should contain only ("author", "author.keyword", "name", "name.keyword", "release_date")
            result.column("author").toList should contain only (
              Str("James S.A. Corey"),
              Str("Dan Simmons"),
              Str("Frank Herbert")
            )
            result.rows.size should be(3)
          }
          "filter in block is used" excludeES (allEs6x, allEs7x, allEs8xBelowEs811x) in {
            val result = dev3EsqlManager.execute("""FROM bookstore | LIMIT 100""")
            result should have statusCode 200
            result.columnNames should contain only (
              "author",
              "author.keyword",
              "name",
              "name.keyword",
              "price",
              "release_date"
            )
            result.column("author").toList should contain only Str("Frank Herbert")
            result.rows.size should be(1)
          }
        }
        "ESQL keywords are not uppercased" excludeES (allEs6x, allEs7x, allEs8xBelowEs811x) in {
          val result = dev1EsqlManager.execute("""fRoM book* | lImIt 100""")
          result should have statusCode 200
          result.columnNames should contain only ("author", "author.keyword", "name", "name.keyword", "release_date")
          result.column("author").toList should contain only (
            Str("James S.A. Corey"),
            Str("Dan Simmons"),
            Str("Frank Herbert")
          )
          result.rows.size should be(3)
        }
      }
      "be bad request (implicitly forbidden)" when {
        "user doesn't have access to given index" when {
          "full index name is used" excludeES (allEs6x, allEs7x, allEs8xBelowEs811x) in {
            val result = dev2EsqlManager.execute("""FROM bookstore | LIMIT 100""")
            result should have statusCode 400
            result.responseJson("error").obj("reason").str should include("Unknown index")
          }
          "wildcard is used" excludeES (allEs6x, allEs7x, allEs8xBelowEs811x, allEs9xAboveEs93x) in {
            val result = dev2EsqlManager.execute("""FROM book* | LIMIT 100""")
            result should have statusCode 400
            result.responseJson("error").obj("reason").str should include("Unknown index")
          }
          "alias is used" excludeES (allEs6x, allEs7x, allEs8xBelowEs811x) in {
            val result = dev2EsqlManager.execute("""FROM bookshop | LIMIT 100""")
            result should have statusCode 400
            result.responseJson("error").obj("reason").str should include("Unknown index")
          }
          "not-existent index name is used" excludeES (allEs6x, allEs7x, allEs8xBelowEs811x) in {
            val result = dev2EsqlManager.execute("""FROM flea_market | LIMIT 100""")
            result should have statusCode 400
            result.responseJson("error").obj("reason").str should include("Unknown index")
          }
        }
        "user rule is not used" when {
          "not-existent index name is used" excludeES (allEs6x, allEs7x, allEs8xBelowEs811x) in {
            val result = adminEsqlManager.execute("""FROM unknown | LIMIT 100""")
            result should have statusCode 400
          }
        }
        "esql query is malformed" excludeES (allEs6x, allEs7x, allEs8xBelowEs811x) in {
          val result = adminEsqlManager.execute("""FROM unescaped-index.name | LIMIT 100""")
          result should have statusCode 400
        }
      }
      "return empty result (implicitly forbidden)" when {
        "user doesn't have access to given index" when {
          "wildcard is used" excludeES (allEs6x, allEs7x, allEs8x, allEs9xBelowEs94x) in {
            val result = dev2EsqlManager.execute("""FROM book* | LIMIT 100""")
            result should have statusCode 200
            result.rows should be(empty)
          }
        }
      }
    }
  }

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
      "ReadonlyREST cannot parse the query, so it cannot hold it to the indices the ACL allowed" excludeES (
        allEs6x,
        allEs7x,
        allEs8xBelowEs811x
      ) in {
        val result = catalogOnlyEsqlManager.execute("""FROM book_catalog | NOT_A_COMMAND""")

        result should have statusCode 403
      }
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
      "ReadonlyREST cannot parse the query, but the ACL narrowed nothing to hold it to" excludeES (
        allEs6x,
        allEs7x,
        allEs8xBelowEs811x
      ) in {
        val result = unrestrictedEsqlManager.execute("""FROM book_catalog | NOT_A_COMMAND""")

        result should have statusCode 400
      }
      "ROR cannot read the LOOKUP JOIN target, but the ACL narrowed nothing to hold the query to" excludeES (
        allEs6x,
        allEs7x,
        allEs8xBelowEs818x
      ) in {
        val result = unrestrictedEsqlManager.execute(
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

object BaseEsqlApiSuite {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    val indexManager = new IndexManager(adminRestClient, esVersion)

    configureBookstore(documentManager, indexManager)
    configureLibrary(documentManager)
    if (Version.greaterOrEqualThan(esVersion, 8, 18, 0)) {
      configureLookupIndices(documentManager, indexManager)
    }
  }

  private def configureBookstore(documentManager: DocumentManager, indexManager: IndexManager): Unit = {
    documentManager.createDocAndAssert(
      "bookstore",
      "stock",
      1,
      ujson.read(
        s"""{"name": "Leviathan Wakes", "author": "James S.A. Corey", "release_date": "2011-06-02", "price": 100}"""
      )
    )
    documentManager.createDocAndAssert(
      "bookstore",
      "stock",
      2,
      ujson.read(
        s"""{"name": "Hyperion", "author": "Dan Simmons", "release_date": "1989-05-26", "price": 200}"""
      )
    )
    documentManager.createDocAndAssert(
      "bookstore",
      "stock",
      3,
      ujson.read(
        s"""{"name": "Dune", "author": "Frank Herbert", "release_date": "1965-06-01", "price": 50}"""
      )
    )
    indexManager.createAliasOf("bookstore", "bookshop").force()
  }

  private def configureLibrary(documentManager: DocumentManager): Unit = {
    documentManager.createDocAndAssert(
      "library",
      "book",
      1,
      ujson.read(
        s"""{"name": "Leviathan Wakes", "author": "James S.A. Corey", "release_date": "2011-06-02", "internal_id": 1}"""
      )
    )
    documentManager.createDocAndAssert(
      "library",
      "book",
      2,
      ujson.read(
        s"""{"name": "Hyperion", "author": "Dan Simmons", "release_date": "1989-05-26", "internal_id": 2}"""
      )
    )
  }

  private def configureLookupIndices(documentManager: DocumentManager, indexManager: IndexManager): Unit = {
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

    indexManager
      .createIndex("book_prices", settings = Some(ujson.read("""{"settings": {"index": {"mode": "lookup"}}}""")))
      .force()
    indexManager
      .createIndex("store_ratings", settings = Some(ujson.read("""{"settings": {"index": {"mode": "lookup"}}}""")))
      .force()

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
