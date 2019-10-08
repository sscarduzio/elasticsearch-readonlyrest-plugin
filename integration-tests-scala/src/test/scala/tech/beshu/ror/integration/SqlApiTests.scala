package tech.beshu.ror.integration

import com.dimafeng.testcontainers.ForAllTestContainer
import org.scalatest.Matchers._
import org.scalatest._
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, ReadonlyRestEsCluster, ReadonlyRestEsClusterContainer}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, SqlApiManager}
import tech.beshu.ror.utils.httpclient.RestClient
import ujson.{Null, Num, Str}

class SqlApiTests extends WordSpec with ForAllTestContainer {

  override val container: ReadonlyRestEsClusterContainer = ReadonlyRestEsCluster.createLocalClusterContainer(
    name = "ROR1",
    rorConfigFileName = "/sql_api/readonlyrest.yml",
    numberOfInstances = 1,
    nodeDataInitializer = SqlApiTests.nodeDataInitializer()
  )

  private lazy val adminSqlManager = new SqlApiManager(container.nodesContainers.head.adminClient)
  private lazy val dev1SqlManager = new SqlApiManager(container.nodesContainers.head.client("dev1", "test"))
  private lazy val dev2SqlManager = new SqlApiManager(container.nodesContainers.head.client("dev2", "test"))

  "SQL query request" when {
    "SELECT command is used" should {
      "be allowed" when {
        "user has access to any index" in {
          val result = adminSqlManager.execute("SELECT * FROM bookstore ORDER BY author")
          result.isSuccess should be(true)
          result.queryResult.size should be(4)
          result.queryResult("author") should be(Vector(Str("Dan Simmons"), Str("Frank Herbert"), Str("James S.A. Corey")))
          result.queryResult("price") should be(Vector(Num(200), Num(50), Num(110)))
        }
        "user has access to given index" in {
          val result = dev1SqlManager.execute("SELECT * FROM bookstore ORDER BY author")
          result.isSuccess should be(true)
          result.queryResult.size should be(4)
          result.queryResult("author") should be(Vector(Str("Dan Simmons"), Str("Frank Herbert"), Str("James S.A. Corey")))
          result.queryResult("price") should be(Vector(Null, Null, Null))
        }
      }
      "be forbidden" when {
        "user doesn't have access to given index" in {
          val result = dev2SqlManager.execute("SELECT * FROM bookstore ORDER BY author")
          result.responseCode should be(401)
        }
      }
    }
    "DESCRIBE TABLE command is used" should {
      "be allowed" when {
        "user has access to any index" in {
          val result = adminSqlManager.execute("DESCRIBE TABLE library1")
          result.isSuccess should be(true)
          result.queryResult.size should be(4)
        }
        "user has access to given index" in {
        }
      }
      "be forbidden" when {
        "user doesn't have access to given index" in {
        }
      }
    }
    "SHOW COLUMNS command is used" should {
      "be allowed" when {
        "user has access to any index" in {
        }
        "user has access to given index" in {
        }
      }
      "be forbidden" when {
        "user doesn't have access to given index" in {
        }
      }
    }
    "SHOW TABLES command is used" should {
      "be allowed" when {
        "user has access to any index" in {
        }
        "user has access to given index" in {
        }
      }
      "be forbidden" when {
        "user doesn't have access to given index" in {
        }
      }
    }
  }
}

object SqlApiTests {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient)
    insertLibrary1Docs(documentManager)
    insertLibrary2Docs(documentManager)
  }

  private def insertLibrary1Docs(documentManager: DocumentManager): Unit = {
    documentManager.createDocAndAssert("/bookstore/book/1", ujson.read(
      s"""{"name": "Leviathan Wakes", "author": "James S.A. Corey", "release_date": "2011-06-02", "price": 100}"""
    ))
    documentManager.createDocAndAssert("/bookstore/book/2", ujson.read(
      s"""{"name": "Hyperion", "author": "Dan Simmons", "release_date": "1989-05-26", "price": 200}"""
    ))
    documentManager.createDocAndAssert("/bookstore/book/3", ujson.read(
      s"""{"name": "Dune", "author": "Frank Herbert", "release_date": "1965-06-01", "price": 50}"""
    ))
  }
  private def insertLibrary2Docs(documentManager: DocumentManager): Unit = {
    documentManager.createDocAndAssert("/library/book/1", ujson.read(
      s"""{"name": "Leviathan Wakes", "author": "James S.A. Corey", "release_date": "2011-06-02", "internal_id": 1}"""
    ))
    documentManager.createDocAndAssert("/library/book/2", ujson.read(
      s"""{"name": "Hyperion", "author": "Dan Simmons", "release_date": "1989-05-26", "internal_id": 2}"""
    ))
  }
}
