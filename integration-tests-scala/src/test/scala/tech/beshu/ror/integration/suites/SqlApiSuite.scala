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

import com.dimafeng.testcontainers.ForAllTestContainer
import org.scalatest._
import tech.beshu.ror.integration.utils.ESVersionSupport
import tech.beshu.ror.utils.containers.generic._
import tech.beshu.ror.utils.containers.generic.providers.{RorConfigFileNameProvider, SingleClient, SingleEsTarget}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, IndexManager, SqlApiManager}
import tech.beshu.ror.utils.httpclient.RestClient
import ujson.{Null, Num, Str}

trait SqlApiSuite
  extends WordSpec
    with ForAllTestContainer
    with EsClusterProvider
    with SingleClient
    with SingleEsTarget
    with RorConfigFileNameProvider
    with ESVersionSupport
    with Matchers {
  this: EsContainerCreator =>

  override val rorConfigFileName = "/sql_api/readonlyrest.yml"

  override lazy val targetEs = container.nodesContainers.head

  override lazy val container = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      rorConfigFileName = rorConfigFileName,
      nodeDataInitializer = SqlApiSuite.nodeDataInitializer(),
      xPackSupport = true
    )
  )

  private lazy val adminSqlManager = new SqlApiManager(adminClient, container.esVersion)
  private lazy val dev1SqlManager = new SqlApiManager(client("dev1", "test"), container.esVersion)
  private lazy val dev2SqlManager = new SqlApiManager(client("dev2", "test"), container.esVersion)

  "SQL query request" when {
    "SELECT query is used" should {
      "be allowed" when {
        "user has no indices rule (has access to any index)" when {
          "full index name is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""SELECT * FROM library""")
            result.isSuccess should be(true)
            result.queryResult.size should be(4)
            result.columnNames should contain only("author", "internal_id", "name", "release_date")
            result.rows.size should be(2)
            result.column("author").toList should contain only(Str("James S.A. Corey"), Str("Dan Simmons"))
            result.column("internal_id").toList should contain only(Num(1), Num(2))
          }
          "full indices names are used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""SELECT * FROM \"bookstore,library\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(5)
            result.column("author").toList should contain only(
              Str("James S.A. Corey"), Str("Dan Simmons"), Str("Frank Herbert")
            )
            result.column("internal_id").toList should contain only(Num(1), Num(2), Null)
          }
          "wildcard is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""SELECT * FROM \"*\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(5)
            result.column("author").toList should contain only(
              Str("James S.A. Corey"), Str("Dan Simmons"), Str("Frank Herbert")
            )
            result.column("internal_id").toList should contain only(Num(1), Num(2), Null)
          }
          "alias is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""SELECT * FROM bookshop""")
            result.isSuccess should be(true)
            result.queryResult.size should be(4)
            result.columnNames should contain only("author", "name", "price", "release_date")
            result.rows.size should be(3)
            result.column("author").toList should contain only(Str("James S.A. Corey"), Str("Dan Simmons"), Str("Frank Herbert"))
            result.column("price").toList should contain only(Num(100), Num(200), Num(50))
          }
        }
        "user has access to given index" when {
          "full index name is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = dev1SqlManager.execute("""SELECT * FROM bookstore""")
            result.isSuccess should be(true)
            result.queryResult.size should be(4)
            result.columnNames should contain only("author", "name", "price", "release_date")
            result.rows.size should be(3)
            result.column("author").toList should contain only(Str("James S.A. Corey"), Str("Dan Simmons"), Str("Frank Herbert"))
            result.column("price").toList should contain only Null
          }
          "full indices names are used and one of them is not allowed" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = dev1SqlManager.execute("""SELECT * FROM \"bookstore,library\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(4)
            result.columnNames should contain only("author", "name", "price", "release_date")
            result.rows.size should be(3)
            result.column("author").toList should contain only(Str("James S.A. Corey"), Str("Dan Simmons"), Str("Frank Herbert"))
            result.column("price").toList should contain only Null
          }
          "wildcard is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = dev1SqlManager.execute("""SELECT * FROM \"book*\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(4)
            result.columnNames should contain only("author", "name", "price", "release_date")
            result.rows.size should be(3)
            result.column("author").toList should contain only(Str("James S.A. Corey"), Str("Dan Simmons"), Str("Frank Herbert"))
            result.column("price").toList should contain only Null
          }
          "alias is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = dev1SqlManager.execute("""SELECT * FROM \"bookshop\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(4)
            result.columnNames should contain only("author", "name", "price", "release_date")
            result.rows.size should be(3)
            result.column("author").toList should contain only(Str("James S.A. Corey"), Str("Dan Simmons"), Str("Frank Herbert"))
            result.column("price").toList should contain only Null
          }
        }
      }
      "be forbidden" when {
        "user doesn't have access to given index" when {
          "full index name is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = dev2SqlManager.execute("""SELECT * FROM bookstore""")
            result.isBadRequest should be(true)
            result.responseJson("error").obj("reason").str should include("Unknown index")
          }
          "wildcard is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = dev2SqlManager.execute("""SELECT * FROM \"book*\"""")
            result.isBadRequest should be(true)
            result.responseJson("error").obj("reason").str should include("Unknown index")
          }
          "alias is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = dev2SqlManager.execute("""SELECT * FROM bookshop""")
            result.isBadRequest should be(true)
            result.responseJson("error").obj("reason").str should include("Unknown index")
          }
          "not-existent index name is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = dev2SqlManager.execute("""SELECT * FROM flea_market""")
            result.isBadRequest should be(true)
            result.responseJson("error").obj("reason").str should include("Unknown index")
          }
        }
      }
      "be malformed" when {
        "user rule is not used" when {
          "not-existent index name is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""SELECT * FROM unknown""")
            result.isSuccess should be(false)
            result.responseCode should be(400)
          }
        }
      }
    }
    "DESCRIBE TABLE command is used" should {
      "be allowed" when {
        "user has no indices rule (has access to any index)" when {
          "full index name is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""DESCRIBE library""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should contain only(
              "author", "author.keyword", "internal_id", "name", "name.keyword", "release_date"
            )
          }
          "full indices names are used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""DESCRIBE \"bookstore,library\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should contain only(
              "author", "author.keyword", "internal_id", "name", "name.keyword", "price", "release_date"
            )
          }
          "wildcard is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""DESCRIBE \"*\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should contain only(
              "author", "author.keyword", "internal_id", "name", "name.keyword", "price", "release_date"
            )
          }
          "alias is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""DESCRIBE bookshop""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should contain only(
              "author", "author.keyword", "name", "name.keyword", "price", "release_date"
            )
          }
          "not-existent index name is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""DESCRIBE unknown""")
            result.isSuccess should be(true)
            result.queryResult.size should be(0)
          }
        }
        "user has access to given index" when {
          "full index name is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = dev1SqlManager.execute("""DESCRIBE bookstore""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should contain only(
              "author", "author.keyword", "name", "name.keyword", "price", "release_date"
            )
          }
          "full indices names are used and one of them is not allowed" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = dev1SqlManager.execute("""DESCRIBE \"bookstore,library\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should contain only(
              "author", "author.keyword", "name", "name.keyword", "price", "release_date"
            )
          }
          "wildcard is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = dev1SqlManager.execute("""DESCRIBE \"*\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should contain only(
              "author", "author.keyword", "name", "name.keyword", "price", "release_date"
            )
          }
          "alias is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = dev1SqlManager.execute("""DESCRIBE \"bookshop\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should contain only(
              "author", "author.keyword", "name", "name.keyword", "price", "release_date"
            )
          }
        }
      }
      "be forbidden" when {
        "user doesn't have access to given index" when {
          "full index name is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = dev2SqlManager.execute("""DESCRIBE bookstore""")
            result.isSuccess should be(true)
            result.queryResult.size should be(0)
          }
          "wildcard is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = dev2SqlManager.execute("""DESCRIBE \"book*\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(0)
          }
          "alias is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = dev2SqlManager.execute("""DESCRIBE bookshop""")
            result.isSuccess should be(true)
            result.queryResult.size should be(0)
          }
          "not-existent index name is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = dev2SqlManager.execute("""DESCRIBE flea_market""")
            result.isSuccess should be(true)
            result.queryResult.size should be(0)
          }
        }
      }
    }
    "SHOW COLUMNS command is used" should {
      "be allowed" when {
        "user has no indices rule (has access to any index)" when {
          "full index name is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""SHOW COLUMNS IN library""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should contain only(
              "author", "author.keyword", "internal_id", "name", "name.keyword", "release_date"
            )
          }
          "full indices names are used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""SHOW COLUMNS IN \"bookstore,library\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should contain only(
              "author", "author.keyword", "internal_id", "name", "name.keyword", "price", "release_date"
            )
          }
          "wildcard is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""SHOW COLUMNS IN \"*\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should contain only(
              "author", "author.keyword", "internal_id", "name", "name.keyword", "price", "release_date"
            )
          }
          "alias is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""SHOW COLUMNS IN bookshop""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should contain only(
              "author", "author.keyword", "name", "name.keyword", "price", "release_date"
            )
          }
          "not-existent index name is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""SHOW COLUMNS IN unknown""")
            result.isSuccess should be(true)
            result.queryResult.size should be(0)
          }
        }
        "user has access to given index" when {
          "full index name is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = dev1SqlManager.execute("""SHOW COLUMNS IN bookstore""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should contain only(
              "author", "author.keyword", "name", "name.keyword", "price", "release_date"
            )
          }
          "full indices names are used and one of them is not allowed" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = dev1SqlManager.execute("""SHOW COLUMNS FROM \"bookstore,library\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should contain only(
              "author", "author.keyword", "name", "name.keyword", "price", "release_date"
            )
          }
          "wildcard is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = dev1SqlManager.execute("""SHOW COLUMNS FROM \"*\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should contain only(
              "author", "author.keyword", "name", "name.keyword", "price", "release_date"
            )
          }
          "alias is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = dev1SqlManager.execute("""SHOW COLUMNS FROM \"bookshop\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should contain only(
              "author", "author.keyword", "name", "name.keyword", "price", "release_date"
            )
          }
        }
      }
      "be forbidden" when {
        "user doesn't have access to given index" when {
          "full index name is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = dev2SqlManager.execute("""SHOW COLUMNS FROM bookstore""")
            result.isSuccess should be(true)
            result.queryResult.size should be(0)
          }
          "wildcard is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = dev2SqlManager.execute("""SHOW COLUMNS FROM \"book*\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(0)
          }
          "alias is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = dev2SqlManager.execute("""SHOW COLUMNS FROM bookshop""")
            result.isSuccess should be(true)
            result.queryResult.size should be(0)
          }
          "not-existent index name is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = dev2SqlManager.execute("""SHOW COLUMNS FROM flea_market""")
            result.isSuccess should be(true)
            result.queryResult.size should be(0)
          }
        }
      }
    }
    "SHOW TABLES command is used" should {
      "be allowed" when {
        "user has no indices rule (has access to any index)" when {
          "full index name is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""SHOW TABLES library""")
            result.isSuccess should be(true)
            result.column("name").map(_.str).headOption should be(Some("library"))
          }
          "full indices names are used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""SHOW TABLES \"bookstore,library\"""")
            result.isSuccess should be(true)
            result.column("name").map(_.str) should contain only("bookstore", "library")
          }
          "wildcard is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""SHOW TABLES \"*\"""")
            result.isSuccess should be(true)
            result.column("name").map(_.str) should contain only("bookshop", "bookstore", "library")
          }
          "all tables are requested" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""SHOW TABLES""")
            result.isSuccess should be(true)
            result.column("name").map(_.str) should contain only("bookshop", "bookstore", "library")
          }
          "alias is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""SHOW TABLES bookshop""")
            result.isSuccess should be(true)
            result.column("name").map(_.str).headOption should be(Some("bookshop"))
          }
          "not-existent index name is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""SHOW TABLES unknown""")
            result.isSuccess should be(true)
            result.queryResult.size should be(0)
          }
        }
        "user has access to given index" when {
          "full index name is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = dev1SqlManager.execute("""SHOW TABLES bookstore""")
            result.isSuccess should be(true)
            result.column("name").map(_.str).headOption should be(Some("bookstore"))
          }
          "full indices names are used and one of them is not allowed" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = dev1SqlManager.execute("""SHOW TABLES \"bookstore,library\"""")
            result.isSuccess should be(true)
            result.column("name").map(_.str).headOption should be(Some("bookstore"))
          }
          "wildcard is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = dev1SqlManager.execute("""SHOW TABLES \"*\"""")
            result.isSuccess should be(true)
            result.column("name").map(_.str).headOption should be(Some("bookstore"))
          }
          "all tables are requested" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = dev1SqlManager.execute("""SHOW TABLES""")
            result.isSuccess should be(true)
            result.column("name").map(_.str).headOption should be(Some("bookstore"))
          }
          "alias is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = dev1SqlManager.execute("""SHOW TABLES \"bookshop\"""")
            result.isSuccess should be(true)
            result.column("name").map(_.str).headOption should be(Some("bookstore"))
          }
        }
      }
      "be forbidden" when {
        "user doesn't have access to given index" when {
          "full index name is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = dev2SqlManager.execute("""SHOW TABLES bookstore""")
            result.isSuccess should be(true)
            result.queryResult.size should be(0)
          }
          "wildcard is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = dev2SqlManager.execute("""SHOW TABLES \"book*\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(0)
          }
          "alias is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = dev2SqlManager.execute("""SHOW TABLES bookshop""")
            result.isSuccess should be(true)
            result.queryResult.size should be(0)
          }
          "not-existent index name is used" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
            val result = dev2SqlManager.execute("""SHOW TABLES flea_market""")
            result.isSuccess should be(true)
            result.queryResult.size should be(0)
          }
        }
      }
    }
    "SHOW FUNCTIONS command is used" should {
      "be allowed" when {
        "user has no indices rule (has access to any index)" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
          val result = adminSqlManager.execute("""SHOW FUNCTIONS""")
          result.isSuccess should be(true)
        }
        "user has one index" excludeES("es51x", "es52x", "es53x", "es55x", "es60x", "es61x", "es62x") in {
          val result = dev2SqlManager.execute("""SHOW FUNCTIONS""")
          result.isSuccess should be(true)
        }
      }
    }
  }
}

object SqlApiSuite {

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