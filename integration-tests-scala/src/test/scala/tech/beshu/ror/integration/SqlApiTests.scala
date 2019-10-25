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
package tech.beshu.ror.integration

import com.dimafeng.testcontainers.ForAllTestContainer
import org.scalatest.Matchers._
import org.scalatest._
import tech.beshu.ror.utils.containers.ReadonlyRestEsCluster.AdditionalClusterSettings
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, ReadonlyRestEsCluster, ReadonlyRestEsClusterContainer}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, IndexManager, SqlApiManager}
import tech.beshu.ror.utils.httpclient.RestClient
import ujson.{Null, Num, Str}

class SqlApiTests extends WordSpec with ForAllTestContainer {

  override val container: ReadonlyRestEsClusterContainer = ReadonlyRestEsCluster.createLocalClusterContainer(
    name = "ROR1",
    rorConfigFileName = "/sql_api/readonlyrest.yml",
    clusterSettings = AdditionalClusterSettings(nodeDataInitializer = SqlApiTests.nodeDataInitializer())
  )

  private lazy val adminSqlManager = new SqlApiManager(container.nodesContainers.head.adminClient)
  private lazy val dev1SqlManager = new SqlApiManager(container.nodesContainers.head.client("dev1", "test"))
  private lazy val dev2SqlManager = new SqlApiManager(container.nodesContainers.head.client("dev2", "test"))

  "SQL query request" when {
    "SELECT query is used" should {
      "be allowed" when {
        "user has no indices rule (has access to any index)" when {
          "full index name is used" in {
            val result = adminSqlManager.execute("""SELECT * FROM library""")
            result.isSuccess should be(true)
            result.queryResult.size should be(4)
            result.columnNames should be ("author" :: "internal_id" :: "name" :: "release_date" :: Nil)
            result.rows.size should be (2)
            result.column("author").toList should be (Str("James S.A. Corey") :: Str("Dan Simmons") :: Nil)
            result.column("internal_id").toList should be (Num(1) :: Num(2) :: Nil)
          }
          "full indices names are used" in {
            val result = adminSqlManager.execute("""SELECT * FROM \"bookstore,library\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(5)
            result.column("author").toList should be {
              Str("James S.A. Corey") :: Str("James S.A. Corey") :: Str("Dan Simmons") :: Str("Dan Simmons") :: Str("Frank Herbert") :: Nil
            }
            result.column("internal_id").toList should be {
              Null :: Num(1) :: Null :: Num(2) :: Null :: Nil
            }
          }
          "wildcard is used" in {
            val result = adminSqlManager.execute("""SELECT * FROM \"*\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(5)
            result.column("author").toList should be {
              Str("James S.A. Corey") :: Str("James S.A. Corey") :: Str("Dan Simmons") :: Str("Dan Simmons") :: Str("Frank Herbert") :: Nil
            }
            result.column("internal_id").toList should be {
              Null :: Num(1) :: Null :: Num(2) :: Null :: Nil
            }
          }
          "alias is used" in {
            val result = adminSqlManager.execute("""SELECT * FROM bookshop""")
            result.isSuccess should be(true)
            result.queryResult.size should be(4)
            result.columnNames should be ("author" :: "name" :: "price" :: "release_date" :: Nil)
            result.rows.size should be (3)
            result.column("author").toList should be (Str("James S.A. Corey") :: Str("Dan Simmons") :: Str("Frank Herbert") :: Nil)
            result.column("price").toList should be (Num(100) :: Num(200) :: Num(50) :: Nil)
          }
        }
        "user has access to given index" when {
          "full index name is used" in {
            val result = dev1SqlManager.execute("""SELECT * FROM bookstore""")
            result.isSuccess should be(true)
            result.queryResult.size should be(4)
            result.columnNames should be ("author" :: "name" :: "price" :: "release_date" :: Nil)
            result.rows.size should be (3)
            result.column("author").toList should be (Str("James S.A. Corey") :: Str("Dan Simmons") :: Str("Frank Herbert") :: Nil)
            result.column("price").toList should be (Null :: Null :: Null :: Nil)
            }
          "full indices names are used and one of them is not allowed" in {
            val result = dev1SqlManager.execute("""SELECT * FROM \"bookstore,library\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(4)
            result.columnNames should be ("author" :: "name" :: "price" :: "release_date" :: Nil)
            result.rows.size should be (3)
            result.column("author").toList should be (Str("James S.A. Corey") :: Str("Dan Simmons") :: Str("Frank Herbert") :: Nil)
            result.column("price").toList should be (Null :: Null :: Null :: Nil)
          }
          "wildcard is used" in {
            val result = dev1SqlManager.execute("""SELECT * FROM \"book*\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(4)
            result.columnNames should be ("author" :: "name" :: "price" :: "release_date" :: Nil)
            result.rows.size should be (3)
            result.column("author").toList should be (Str("James S.A. Corey") :: Str("Dan Simmons") :: Str("Frank Herbert") :: Nil)
            result.column("price").toList should be (Null :: Null :: Null :: Nil)
          }
          "alias is used" in {
            val result = dev1SqlManager.execute("""SELECT * FROM \"bookshop\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(4)
            result.columnNames should be ("author" :: "name" :: "price" :: "release_date" :: Nil)
            result.rows.size should be (3)
            result.column("author").toList should be (Str("James S.A. Corey") :: Str("Dan Simmons") :: Str("Frank Herbert") :: Nil)
            result.column("price").toList should be (Null :: Null :: Null :: Nil)
          }
        }
      }
      "be forbidden" when {
        "user doesn't have access to given index" when {
          "full index name is used" in {
            val result = dev2SqlManager.execute("""SELECT * FROM bookstore""")
            result.isForbidden should be(true)
          }
          "wildcard is used" in {
            val result = dev2SqlManager.execute("""SELECT * FROM \"book*\"""")
            result.isForbidden should be(true)
          }
          "alias is used" in {
            val result = dev2SqlManager.execute("""SELECT * FROM bookshop""")
            result.isForbidden should be(true)
          }
          "not-existent index name is used" in {
            val result = dev2SqlManager.execute("""SELECT * FROM flea_market""")
            result.isForbidden should be(true)
          }
        }
      }
      "be malformed" when {
        "user rule is not used" when {
          "not-existent index name is used" in {
            val result = adminSqlManager.execute("""SELECT * FROM unknown""")
            result.isSuccess should be(false)
            result.responseCode should be (400)
          }
        }
      }
    }
    "DESCRIBE TABLE command is used" should {
      "be allowed" when {
        "user has no indices rule (has access to any index)" when {
          "full index name is used" in {
            val result = adminSqlManager.execute("""DESCRIBE library""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should be {
              "author":: "author.keyword" :: "internal_id" :: "name" :: "name.keyword" :: "release_date" :: Nil
            }
          }
          "full indices names are used" in {
            val result = adminSqlManager.execute("""DESCRIBE \"bookstore,library\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should be {
              "author":: "author.keyword" :: "internal_id" :: "name" :: "name.keyword" :: "price" :: "release_date" :: Nil
            }
          }
          "wildcard is used" in {
            val result = adminSqlManager.execute("""DESCRIBE \"*\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should be {
              "author":: "author.keyword" :: "internal_id" :: "name" :: "name.keyword" :: "price" :: "release_date" :: Nil
            }
          }
          "alias is used" in {
            val result = adminSqlManager.execute("""DESCRIBE bookshop""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should be {
              "author":: "author.keyword" :: "name" :: "name.keyword" :: "price" :: "release_date" :: Nil
            }
          }
          "not-existent index name is used" in {
            val result = adminSqlManager.execute("""DESCRIBE unknown""")
            result.isSuccess should be(true)
            result.queryResult.size should be(0)
          }
        }
        "user has access to given index" when {
          "full index name is used" in {
            val result = dev1SqlManager.execute("""DESCRIBE bookstore""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should be {
              "author":: "author.keyword" :: "name" :: "name.keyword" :: "price" :: "release_date" :: Nil
            }
          }
          "full indices names are used and one of them is not allowed" in {
            val result = dev1SqlManager.execute("""DESCRIBE \"bookstore,library\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should be {
              "author":: "author.keyword" :: "name" :: "name.keyword" :: "price" :: "release_date" :: Nil
            }
          }
          "wildcard is used" in {
            val result = dev1SqlManager.execute("""DESCRIBE \"*\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should be {
              "author":: "author.keyword" :: "name" :: "name.keyword" :: "price" :: "release_date" :: Nil
            }
          }
          "alias is used" in {
            val result = dev1SqlManager.execute("""DESCRIBE \"bookshop\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should be {
              "author":: "author.keyword" :: "name" :: "name.keyword" :: "price" :: "release_date" :: Nil
            }
          }
        }
      }
      "be forbidden" when {
        "user doesn't have access to given index" when {
          "full index name is used" in {
            val result = dev2SqlManager.execute("""DESCRIBE bookstore""")
            result.isForbidden should be(true)
          }
          "wildcard is used" in {
            val result = dev2SqlManager.execute("""DESCRIBE \"book*\"""")
            result.isForbidden should be(true)
          }
          "alias is used" in {
            val result = dev2SqlManager.execute("""DESCRIBE bookshop""")
            result.isForbidden should be(true)
          }
          "not-existent index name is used" in {
            val result = dev2SqlManager.execute("""DESCRIBE flea_market""")
            result.isForbidden should be(true)
          }
        }
      }
    }
    "SHOW COLUMNS command is used" should {
      "be allowed" when {
        "user has no indices rule (has access to any index)" when {
          "full index name is used" in {
            val result = adminSqlManager.execute("""SHOW COLUMNS IN library""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should be {
              "author":: "author.keyword" :: "internal_id" :: "name" :: "name.keyword" :: "release_date" :: Nil
            }
          }
          "full indices names are used" in {
            val result = adminSqlManager.execute("""SHOW COLUMNS IN \"bookstore,library\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should be {
              "author":: "author.keyword" :: "internal_id" :: "name" :: "name.keyword" :: "price" :: "release_date" :: Nil
            }
          }
          "wildcard is used" in {
            val result = adminSqlManager.execute("""SHOW COLUMNS IN \"*\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should be {
              "author":: "author.keyword" :: "internal_id" :: "name" :: "name.keyword" :: "price" :: "release_date" :: Nil
            }
          }
          "alias is used" in {
            val result = adminSqlManager.execute("""SHOW COLUMNS IN bookshop""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should be {
              "author":: "author.keyword" :: "name" :: "name.keyword" :: "price" :: "release_date" :: Nil
            }
          }
          "not-existent index name is used" in {
            val result = adminSqlManager.execute("""SHOW COLUMNS IN unknown""")
            result.isSuccess should be(true)
            result.queryResult.size should be(0)
          }
        }
        "user has access to given index" when {
          "full index name is used" in {
            val result = dev1SqlManager.execute("""SHOW COLUMNS IN bookstore""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should be {
              "author":: "author.keyword" :: "name" :: "name.keyword" :: "price" :: "release_date" :: Nil
            }
          }
          "full indices names are used and one of them is not allowed" in {
            val result = dev1SqlManager.execute("""SHOW COLUMNS FROM \"bookstore,library\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should be {
              "author":: "author.keyword" :: "name" :: "name.keyword" :: "price" :: "release_date" :: Nil
            }
          }
          "wildcard is used" in {
            val result = dev1SqlManager.execute("""SHOW COLUMNS FROM \"*\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should be {
              "author":: "author.keyword" :: "name" :: "name.keyword" :: "price" :: "release_date" :: Nil
            }
          }
          "alias is used" in {
            val result = dev1SqlManager.execute("""SHOW COLUMNS FROM \"bookshop\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should be {
              "author":: "author.keyword" :: "name" :: "name.keyword" :: "price" :: "release_date" :: Nil
            }
          }
        }
      }
      "be forbidden" when {
        "user doesn't have access to given index" when {
          "full index name is used" in {
            val result = dev2SqlManager.execute("""SHOW COLUMNS FROM bookstore""")
            result.isForbidden should be(true)
          }
          "wildcard is used" in {
            val result = dev2SqlManager.execute("""SHOW COLUMNS FROM \"book*\"""")
            result.isForbidden should be(true)
          }
          "alias is used" in {
            val result = dev2SqlManager.execute("""SHOW COLUMNS FROM bookshop""")
            result.isForbidden should be(true)
          }
          "not-existent index name is used" in {
            val result = dev2SqlManager.execute("""SHOW COLUMNS FROM flea_market""")
            result.isForbidden should be(true)
          }
        }
      }
    }
    "SHOW TABLES command is used" should {
      "be allowed" when {
        "user has no indices rule (has access to any index)" when {
          "full index name is used" in {
            val result = adminSqlManager.execute("""SHOW TABLES library""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.queryResult("name").arr.map(_.str).toList should be { "library" :: Nil }
          }
          "full indices names are used" in {
            val result = adminSqlManager.execute("""SHOW TABLES \"bookstore,library\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.queryResult("name").arr.map(_.str).toList should be { "bookstore" :: "library" :: Nil }
          }
          "wildcard is used" in {
            val result = adminSqlManager.execute("""SHOW TABLES \"*\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.queryResult("name").arr.map(_.str).toList should be { "bookshop" :: "bookstore" :: "library" :: Nil }
          }
          "all tables are requested" in {
            val result = adminSqlManager.execute("""SHOW TABLES""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.queryResult("name").arr.map(_.str).toList should be { "bookshop" :: "bookstore" :: "library" :: Nil }
          }
          "alias is used" in {
            val result = adminSqlManager.execute("""SHOW TABLES bookshop""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.queryResult("name").arr.map(_.str).toList should be { "bookshop" :: Nil }
          }
          "not-existent index name is used" in {
            val result = adminSqlManager.execute("""SHOW TABLES unknown""")
            result.isSuccess should be(true)
            result.queryResult.size should be(0)
          }
        }
        "user has access to given index" when {
          "full index name is used" in {
            val result = dev1SqlManager.execute("""SHOW TABLES bookstore""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.queryResult("name").arr.map(_.str).toList should be { "bookstore" :: Nil }
          }
          "full indices names are used and one of them is not allowed" in {
            val result = dev1SqlManager.execute("""SHOW TABLES \"bookstore,library\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.queryResult("name").arr.map(_.str).toList should be { "bookstore" :: Nil }
          }
          "wildcard is used" in {
            val result = dev1SqlManager.execute("""SHOW TABLES \"*\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.queryResult("name").arr.map(_.str).toList should be { "bookstore" :: Nil }
          }
          "all tables are requested" in {
            val result = dev1SqlManager.execute("""SHOW TABLES""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.queryResult("name").arr.map(_.str).toList should be { "bookstore" :: Nil }
          }
          "alias is used" in {
            val result = dev1SqlManager.execute("""SHOW TABLES \"bookshop\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.queryResult("name").arr.map(_.str).toList should be { "bookstore" :: Nil }
          }
        }
      }
      "be forbidden" when {
        "user doesn't have access to given index" when {
          "full index name is used" in {
            val result = dev2SqlManager.execute("""SHOW TABLES bookstore""")
            result.isForbidden should be(true)
          }
          "wildcard is used" in {
            val result = dev2SqlManager.execute("""SHOW TABLES \"book*\"""")
            result.isForbidden should be(true)
          }
          "alias is used" in {
            val result = dev2SqlManager.execute("""SHOW TABLES bookshop""")
            result.isForbidden should be(true)
          }
          "not-existent index name is used" in {
            val result = dev2SqlManager.execute("""SHOW TABLES flea_market""")
            result.isForbidden should be(true)
          }
        }
      }
    }
    "SHOW FUNCTIONS command is used" should {
      "be allowed" when {
        "user has no indices rule (has access to any index)" in {
          val result = adminSqlManager.execute("""SHOW FUNCTIONS""")
          result.isSuccess should be(true)
        }
        "user has one index" in {
          val result = dev2SqlManager.execute("""SHOW FUNCTIONS""")
          result.isSuccess should be(true)
        }
      }
    }
  }
}

object SqlApiTests {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient)
    val indexManager = new IndexManager(adminRestClient)
    configureBookstore(documentManager, indexManager)
    configureLibrary(documentManager)
  }

  private def configureBookstore(documentManager: DocumentManager, indexManager: IndexManager): Unit = {
    documentManager.createDocAndAssert("/bookstore/stock/1", ujson.read(
      s"""{"name": "Leviathan Wakes", "author": "James S.A. Corey", "release_date": "2011-06-02", "price": 100}"""
    ))
    documentManager.createDocAndAssert("/bookstore/stock/2", ujson.read(
      s"""{"name": "Hyperion", "author": "Dan Simmons", "release_date": "1989-05-26", "price": 200}"""
    ))
    documentManager.createDocAndAssert("/bookstore/stock/3", ujson.read(
      s"""{"name": "Dune", "author": "Frank Herbert", "release_date": "1965-06-01", "price": 50}"""
    ))
    indexManager.createAliasAndAssert("bookstore", "bookshop")
  }
  private def configureLibrary(documentManager: DocumentManager): Unit = {
    documentManager.createDocAndAssert("/library/book/1", ujson.read(
      s"""{"name": "Leviathan Wakes", "author": "James S.A. Corey", "release_date": "2011-06-02", "internal_id": 1}"""
    ))
    documentManager.createDocAndAssert("/library/book/2", ujson.read(
      s"""{"name": "Hyperion", "author": "Dan Simmons", "release_date": "1989-05-26", "internal_id": 2}"""
    ))
  }
}
