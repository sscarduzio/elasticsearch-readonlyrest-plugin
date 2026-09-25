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
import tech.beshu.ror.integration.suites.base.BaseSqlApiSuite.nodeDataInitializer
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyWordSpecLike, PluginTestSupport}
import tech.beshu.ror.utils.TestUjson.ujson
import tech.beshu.ror.utils.TestUjson.ujson.{Null, Num, Str}
import tech.beshu.ror.utils.containers.*
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, IndexManager, SqlApiManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers

trait BaseSqlApiSuite
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

  private lazy val adminSqlManager = new SqlApiManager(basicAuthClient("sqladmin", "pass"), esVersionUsed)
  private lazy val dev1SqlManager = new SqlApiManager(basicAuthClient("dev1sql", "test"), esVersionUsed)
  private lazy val dev2SqlManager = new SqlApiManager(basicAuthClient("dev2sql", "test"), esVersionUsed)
  private lazy val dev3SqlManager = new SqlApiManager(basicAuthClient("dev3sql", "test"), esVersionUsed)

  "SQL query request" when {
    "SELECT query is used" should {
      "be allowed" when {
        "user has no indices rule (has access to any index)" when {
          "full index name is used" in {
            val result = adminSqlManager.execute("""SELECT * FROM library""")
            result should have statusCode 200
            result.queryResult.size should be(4)
            result.columnNames should contain only ("author", "internal_id", "name", "release_date")
            result.rows.size should be(2)
            result.column("author").toList should contain only (Str("James S.A. Corey"), Str("Dan Simmons"))
            result.column("internal_id").toList should contain only (Num(1), Num(2))
          }
          "full indices names are used" in {
            val result = adminSqlManager.execute("""SELECT * FROM \"bookstore,library\"""")
            result should have statusCode 200
            result.queryResult.size should be(5)
            result.column("author").toList should contain only (
              Str("James S.A. Corey"),
              Str("Dan Simmons"),
              Str("Frank Herbert")
            )
            result.column("internal_id").toList should contain only (Num(1), Num(2), Null)
          }
          "wildcard is used" in {
            val result = adminSqlManager.execute("""SELECT * FROM \"*\"""")
            result should have statusCode 200
            result.queryResult.size should be(5)
            result.column("author").toList should contain only (
              Str("James S.A. Corey"),
              Str("Dan Simmons"),
              Str("Frank Herbert")
            )
            result.column("internal_id").toList should contain only (Num(1), Num(2), Null)
          }
          "alias is used" in {
            val result = adminSqlManager.execute("""SELECT * FROM bookshop""")
            result should have statusCode 200
            result.queryResult.size should be(4)
            result.columnNames should contain only ("author", "name", "price", "release_date")
            result.rows.size should be(3)
            result.column("author").toList should contain only (
              Str("James S.A. Corey"),
              Str("Dan Simmons"),
              Str("Frank Herbert")
            )
            result.column("price").toList should contain only (Num(100), Num(200), Num(50))
          }
        }
        "user has access to given index" when {
          "full index name is used" in {
            val result = dev1SqlManager.execute("""SELECT * FROM bookstore""")
            result should have statusCode 200
            result.queryResult.size should be(3)
            result.columnNames should contain only ("author", "name", "release_date")
            result.rows.size should be(3)
            result.column("author").toList should contain only (
              Str("James S.A. Corey"),
              Str("Dan Simmons"),
              Str("Frank Herbert")
            )
          }
          "full indices names are used and one of them is not allowed" in {
            val result = dev1SqlManager.execute("""SELECT * FROM \"bookstore,library\"""")
            result should have statusCode 200
            result.queryResult.size should be(3)
            result.columnNames should contain only ("author", "name", "release_date")
            result.rows.size should be(3)
            result.column("author").toList should contain only (
              Str("James S.A. Corey"),
              Str("Dan Simmons"),
              Str("Frank Herbert")
            )
          }
          "wildcard is used" in {
            val result = dev1SqlManager.execute("""SELECT * FROM \"book*\"""")
            result should have statusCode 200
            result.queryResult.size should be(3)
            result.columnNames should contain only ("author", "name", "release_date")
            result.rows.size should be(3)
            result.column("author").toList should contain only (
              Str("James S.A. Corey"),
              Str("Dan Simmons"),
              Str("Frank Herbert")
            )
          }
          "alias is used" in {
            val result = dev1SqlManager.execute("""SELECT * FROM \"bookshop\"""")
            result should have statusCode 200
            result.queryResult.size should be(3)
            result.columnNames should contain only ("author", "name", "release_date")
            result.rows.size should be(3)
            result.column("author").toList should contain only (
              Str("James S.A. Corey"),
              Str("Dan Simmons"),
              Str("Frank Herbert")
            )
          }
          "filter in block is used" in {
            val result = dev3SqlManager.execute("""SELECT * FROM bookstore""")
            result should have statusCode 200
            result.queryResult.size should be(4)
            result.columnNames should contain only ("author", "name", "price", "release_date")
            result.rows.size should be(1)
            result.column("author").toList should contain only Str("Frank Herbert")
          }
        }
        "SQL keywords are not uppercased" in {
          val result = dev1SqlManager.execute("""SeLeCt * fRoM \"book*\"""")
          result should have statusCode 200
          result.queryResult.size should be(3)
          result.columnNames should contain only ("author", "name", "release_date")
          result.rows.size should be(3)
          result.column("author").toList should contain only (
            Str("James S.A. Corey"),
            Str("Dan Simmons"),
            Str("Frank Herbert")
          )
        }
      }
      "be bad request" when {
        "user doesn't have access to given index" when {
          "full index name is used" in {
            val result = dev2SqlManager.execute("""SELECT * FROM bookstore""")
            result should have statusCode 400
            result.responseJson("error").obj("reason").str should include("Unknown index")
          }
          "wildcard is used" in {
            val result = dev2SqlManager.execute("""SELECT * FROM \"book*\"""")
            result should have statusCode 400
            result.responseJson("error").obj("reason").str should include("Unknown index")
          }
          "alias is used" in {
            val result = dev2SqlManager.execute("""SELECT * FROM bookshop""")
            result should have statusCode 400
            result.responseJson("error").obj("reason").str should include("Unknown index")
          }
          "not-existent index name is used" in {
            val result = dev2SqlManager.execute("""SELECT * FROM flea_market""")
            result should have statusCode 400
            result.responseJson("error").obj("reason").str should include("Unknown index")
          }
        }
      }
      "be malformed" when {
        "user rule is not used" when {
          "not-existent index name is used" in {
            val result = adminSqlManager.execute("""SELECT * FROM unknown""")
            result should have statusCode 400
          }
        }
        "sql query is malformed" in {
          val result = adminSqlManager.execute("""SELECT * FROM unescaped-index.name""")
          result should have statusCode 400
        }
      }
    }
    "DESCRIBE TABLE command is used" should {
      "be allowed" when {
        "user has no indices rule (has access to any index)" when {
          "full index name is used" in {
            val result = adminSqlManager.execute("""DESCRIBE library""")
            result should have statusCode 200
            result.queryResult.keys should contain allOf ("column", "type")
            result.column("column").map(_.str) should contain only (
              "author",
              "author.keyword",
              "internal_id",
              "name",
              "name.keyword",
              "release_date"
            )
          }
          "full indices names are used" in {
            val result = adminSqlManager.execute("""DESCRIBE \"bookstore,library\"""")
            result should have statusCode 200
            result.queryResult.keys should contain allOf ("column", "type")
            result.column("column").map(_.str) should contain only (
              "author",
              "author.keyword",
              "internal_id",
              "name",
              "name.keyword",
              "price",
              "release_date"
            )
          }
          "wildcard is used" in {
            val result = adminSqlManager.execute("""DESCRIBE \"*\"""")
            result should have statusCode 200
            result.queryResult.keys should contain allOf ("column", "type")
            result.column("column").map(_.str) should contain only (
              "author",
              "author.keyword",
              "internal_id",
              "name",
              "name.keyword",
              "price",
              "release_date"
            )
          }
          "alias is used" in {
            val result = adminSqlManager.execute("""DESCRIBE bookshop""")
            result should have statusCode 200
            result.queryResult.keys should contain allOf ("column", "type")
            result.column("column").map(_.str) should contain only (
              "author",
              "author.keyword",
              "name",
              "name.keyword",
              "price",
              "release_date"
            )
          }
          "not-existent index name is used" in {
            val result = adminSqlManager.execute("""DESCRIBE unknown""")
            result should have statusCode 200
            result.queryResult.size should be(0)
          }
        }
        "user has access to given index" when {
          "full index name is used" in {
            val result = dev1SqlManager.execute("""DESCRIBE bookstore""")
            result should have statusCode 200
            result.queryResult.keys should contain allOf ("column", "type")
            result.column("column").map(_.str) should contain only (
              "author",
              "author.keyword",
              "name",
              "name.keyword",
              "price",
              "release_date"
            )
          }
          "full indices names are used and one of them is not allowed" in {
            val result = dev1SqlManager.execute("""DESCRIBE \"bookstore,library\"""")
            result should have statusCode 200
            result.queryResult.keys should contain allOf ("column", "type")
            result.column("column").map(_.str) should contain only (
              "author",
              "author.keyword",
              "name",
              "name.keyword",
              "price",
              "release_date"
            )
          }
          "wildcard is used" in {
            val result = dev1SqlManager.execute("""DESCRIBE \"*\"""")
            result should have statusCode 200
            result.queryResult.keys should contain allOf ("column", "type")
            result.column("column").map(_.str) should contain only (
              "author",
              "author.keyword",
              "name",
              "name.keyword",
              "price",
              "release_date"
            )
          }
          "alias is used" in {
            val result = dev1SqlManager.execute("""DESCRIBE \"bookshop\"""")
            result should have statusCode 200
            result.queryResult.keys should contain allOf ("column", "type")
            result.column("column").map(_.str) should contain only (
              "author",
              "author.keyword",
              "name",
              "name.keyword",
              "price",
              "release_date"
            )
          }
        }
      }
      "be forbidden" when {
        "user doesn't have access to given index" when {
          "full index name is used" in {
            val result = dev2SqlManager.execute("""DESCRIBE bookstore""")
            result should have statusCode 200
            result.queryResult.size should be(0)
          }
          "wildcard is used" in {
            val result = dev2SqlManager.execute("""Describe \"book*\"""")
            result should have statusCode 200
            result.queryResult.size should be(0)
          }
          "alias is used" in {
            val result = dev2SqlManager.execute("""DESCRIBE bookshop""")
            result should have statusCode 200
            result.queryResult.size should be(0)
          }
          "not-existent index name is used" in {
            val result = dev2SqlManager.execute("""DESCRIBE flea_market""")
            result should have statusCode 200
            result.queryResult.size should be(0)
          }
        }
      }
    }
    "SHOW COLUMNS command is used" should {
      "be allowed" when {
        "user has no indices rule (has access to any index)" when {
          "full index name is used" in {
            val result = adminSqlManager.execute("""SHOW COLUMNS IN library""")
            result should have statusCode 200
            result.queryResult.keys should contain allOf ("column", "type")
            result.column("column").map(_.str) should contain only (
              "author",
              "author.keyword",
              "internal_id",
              "name",
              "name.keyword",
              "release_date"
            )
          }
          "full indices names are used" in {
            val result = adminSqlManager.execute("""SHOW COLUMNS IN \"bookstore,library\"""")
            result should have statusCode 200
            result.queryResult.keys should contain allOf ("column", "type")
            result.column("column").map(_.str) should contain only (
              "author",
              "author.keyword",
              "internal_id",
              "name",
              "name.keyword",
              "price",
              "release_date"
            )
          }
          "wildcard is used" in {
            val result = adminSqlManager.execute("""SHOW COLUMNS IN \"*\"""")
            result should have statusCode 200
            result.queryResult.keys should contain allOf ("column", "type")
            result.column("column").map(_.str) should contain only (
              "author",
              "author.keyword",
              "internal_id",
              "name",
              "name.keyword",
              "price",
              "release_date"
            )
          }
          "alias is used" in {
            val result = adminSqlManager.execute("""SHOW COLUMNS IN bookshop""")
            result should have statusCode 200
            result.queryResult.keys should contain allOf ("column", "type")
            result.column("column").map(_.str) should contain only (
              "author",
              "author.keyword",
              "name",
              "name.keyword",
              "price",
              "release_date"
            )
          }
          "not-existent index name is used" in {
            val result = adminSqlManager.execute("""SHOW COLUMNS IN unknown""")
            result should have statusCode 200
            result.queryResult.size should be(0)
          }
        }
        "user has access to given index" when {
          "full index name is used" in {
            val result = dev1SqlManager.execute("""SHOW COLUMNS IN bookstore""")
            result should have statusCode 200
            result.queryResult.keys should contain allOf ("column", "type")
            result.column("column").map(_.str) should contain only (
              "author",
              "author.keyword",
              "name",
              "name.keyword",
              "price",
              "release_date"
            )
          }
          "full indices names are used and one of them is not allowed" in {
            val result = dev1SqlManager.execute("""SHOW COLUMNS from \"bookstore,library\"""")
            result should have statusCode 200
            result.queryResult.keys should contain allOf ("column", "type")
            result.column("column").map(_.str) should contain only (
              "author",
              "author.keyword",
              "name",
              "name.keyword",
              "price",
              "release_date"
            )
          }
          "wildcard is used" in {
            val result = dev1SqlManager.execute("""SHOW COLUMNS FROM \"*\"""")
            result should have statusCode 200
            result.queryResult.keys should contain allOf ("column", "type")
            result.column("column").map(_.str) should contain only (
              "author",
              "author.keyword",
              "name",
              "name.keyword",
              "price",
              "release_date"
            )
          }
          "alias is used" in {
            val result = dev1SqlManager.execute("""SHOW COLUMNS FROM \"bookshop\"""")
            result should have statusCode 200
            result.queryResult.keys should contain allOf ("column", "type")
            result.column("column").map(_.str) should contain only (
              "author",
              "author.keyword",
              "name",
              "name.keyword",
              "price",
              "release_date"
            )
          }
        }
      }
      "be forbidden" when {
        "user doesn't have access to given index" when {
          "full index name is used" in {
            val result = dev2SqlManager.execute("""SHOW COLUMNS FROM bookstore""")
            result should have statusCode 200
            result.queryResult.size should be(0)
          }
          "wildcard is used" in {
            val result = dev2SqlManager.execute("""SHOW COLUMNS FROM \"book*\"""")
            result should have statusCode 200
            result.queryResult.size should be(0)
          }
          "alias is used" in {
            val result = dev2SqlManager.execute("""SHOW COLUMNS FROM bookshop""")
            result should have statusCode 200
            result.queryResult.size should be(0)
          }
          "not-existent index name is used" in {
            val result = dev2SqlManager.execute("""SHOW COLUMNS FROM flea_market""")
            result should have statusCode 200
            result.queryResult.size should be(0)
          }
        }
      }
    }
    "SHOW TABLES command is used" should {
      "be allowed" when {
        "user has no indices rule (has access to any index)" when {
          "full index name is used" in {
            val result = adminSqlManager.execute("""SHOW TABLES library""")
            result should have statusCode 200
            result.column("name").map(_.str).headOption should be(Some("library"))
          }
          "full indices names are used" in {
            val result = adminSqlManager.execute("""SHOW TABLES \"bookstore,library\"""")
            result should have statusCode 200
            result.column("name").map(_.str) should contain only ("bookstore", "library")
          }
          "wildcard is used" in {
            val result = adminSqlManager.execute("""SHOW TABLES \"*\"""")
            result should have statusCode 200
            result.column("name").map(_.str) should contain only ("bookshop", "bookstore", "library")
          }
          "all tables are requested" in {
            val result = adminSqlManager.execute("""SHOW TABLES""")
            result should have statusCode 200
            result.column("name").map(_.str) should contain only ("bookshop", "bookstore", "library")
          }
          "alias is used" in {
            val result = adminSqlManager.execute("""SHOW TABLES bookshop""")
            result should have statusCode 200
            result.column("name").map(_.str).headOption should be(Some("bookshop"))
          }
          "not-existent index name is used" in {
            val result = adminSqlManager.execute("""SHOW TABLES unknown""")
            result should have statusCode 200
            result.queryResult.size should be(0)
          }
        }
        "user has access to given index" when {
          "full index name is used" in {
            val result = dev1SqlManager.execute("""SHOW TABLES bookstore""")
            result should have statusCode 200
            result.column("name").map(_.str).headOption should be(Some("bookstore"))
          }
          "full indices names are used and one of them is not allowed" in {
            val result = dev1SqlManager.execute("""SHOW TABLES \"bookstore,library\"""")
            result should have statusCode 200
            result.column("name").map(_.str).headOption should be(Some("bookstore"))
          }
          "wildcard is used" in {
            val result = dev1SqlManager.execute("""SHOW TABLES \"*\"""")
            result should have statusCode 200
            result.column("name").map(_.str).headOption should be(Some("bookstore"))
          }
          "all tables are requested" in {
            val result = dev1SqlManager.execute("""SHOW TABLES""")
            result should have statusCode 200
            result.column("name").map(_.str).headOption should be(Some("bookstore"))
          }
          "alias is used" in {
            val result = dev1SqlManager.execute("""SHOW TABLES \"bookshop\"""")
            result should have statusCode 200
            result.column("name").map(_.str).headOption should be(Some("bookstore"))
          }
        }
      }
      "be forbidden" when {
        "user doesn't have access to given index" when {
          "full index name is used" in {
            val result = dev2SqlManager.execute("""SHOW TABLES bookstore""")
            result should have statusCode 200
            result.queryResult.size should be(0)
          }
          "wildcard is used" in {
            val result = dev2SqlManager.execute("""SHOW TABLES \"book*\"""")
            result should have statusCode 200
            result.queryResult.size should be(0)
          }
          "alias is used" in {
            val result = dev2SqlManager.execute("""SHOW TABLES bookshop""")
            result should have statusCode 200
            result.queryResult.size should be(0)
          }
          "not-existent index name is used" in {
            val result = dev2SqlManager.execute("""SHOW TABLES flea_market""")
            result should have statusCode 200
            result.queryResult.size should be(0)
          }
        }
      }
    }
    "SHOW FUNCTIONS command is used" should {
      "be allowed" when {
        "user has no indices rule (has access to any index)" in {
          val result = adminSqlManager.execute("""SHOW FUNCTIONS""")
          result should have statusCode 200
        }
        "user has one index" in {
          val result = dev2SqlManager.execute("""SHOW FUNCTIONS""")
          result should have statusCode 200
        }
      }
    }
    "a statement that names no table is used" should {
      "be allowed for a user who has access to some indices only" in {
        val result = dev1SqlManager.execute("""SELECT 1 + 1 AS two""")
        result should have statusCode 200
        result.column("two").toList should contain only Num(2)
      }
    }
    "a command matches index names with a LIKE pattern" should {
      "list only the tables the user has access to" in {
        val result = dev1SqlManager.execute("""SHOW TABLES LIKE 'book%'""")
        result should have statusCode 200
        result.column("name").map(_.str) should contain only "bookstore"
      }
      "list no table the user doesn't have access to" in {
        val result = dev2SqlManager.execute("""SHOW TABLES LIKE 'book%'""")
        result should have statusCode 200
        result.queryResult.size should be(0)
      }
      "show no column of a table the user doesn't have access to" in {
        val result = dev2SqlManager.execute("""SHOW COLUMNS IN LIKE 'book%'""")
        result should have statusCode 200
        result.queryResult.size should be(0)
      }
    }
    "the index names are substituted in the query" should {
      "leave a literal that reads like the table alone" in {
        val result = dev1SqlManager.execute("""SELECT name FROM bookshop WHERE 'bookshop' = CONCAT('book', 'shop')""")
        result should have statusCode 200
        result.rows.size should be(3)
      }
      "leave text written before the FROM keyword alone" in {
        val result = dev1SqlManager.execute("""SELECT 'FROMAGE' AS lit, name FROM \"book*\"""")
        result should have statusCode 200
        result.column("lit").map(_.str) should contain only "FROMAGE"
      }
      "rewrite a query that writes the FROM keyword in lower case" in {
        val result = dev1SqlManager.execute("""select 'book*' as lit, name from \"book*\"""")
        result should have statusCode 200
        result.column("lit").map(_.str) should contain only "book*"
        result.rows.size should be(3)
      }
      "quote the indices an alias written without quotes resolves to" in {
        val result = adminSqlManager.execute("""SELECT name FROM library_and_bookstore""")
        result should have statusCode 200
        result.rows.size should be(5)
      }
      "read a table written in backticks" in {
        val result = dev1SqlManager.execute("""SELECT name FROM `bookstore`""")
        result should have statusCode 200
        result.rows.size should be(3)
      }
      "rewrite the table written after a character that takes two UTF-16 units" in {
        val result = dev1SqlManager.execute("""SELECT '\ud83d\ude00' AS e, name FROM \"book*\"""")
        result should have statusCode 200
        result.rows.size should be(3)
      }
    }
    "a query is read page by page" should {
      "return every page to a user who has access to some indices only" in {
        val firstPage = dev1SqlManager.execute("SELECT author FROM bookstore", fetchSize = 1)
        firstPage should have statusCode 200
        firstPage.rows.size should be(1)
        val secondPage = dev1SqlManager.nextPage(firstPage.responseJson("cursor").str)
        secondPage should have statusCode 200
        secondPage.rows.size should be(1)
      }
    }
  }

}

object BaseSqlApiSuite {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    val indexManager = new IndexManager(adminRestClient, esVersion)

    configureBookstore(documentManager, indexManager)
    configureLibrary(documentManager)
    indexManager.createAliasOf("bookstore,library", "library_and_bookstore").force()
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

}
