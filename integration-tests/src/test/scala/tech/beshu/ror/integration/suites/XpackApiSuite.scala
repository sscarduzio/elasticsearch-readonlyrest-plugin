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

import monix.execution.atomic.Atomic
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpec}
import tech.beshu.ror.integration.suites.XpackApiSuite.NextRollupJobName
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.integration.utils.ESVersionSupport
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsClusterContainer, EsClusterSettings, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, IndexManager, ScriptManager, SearchManager, SqlApiManager}
import tech.beshu.ror.utils.httpclient.RestClient
import ujson.{Null, Num, Str}

trait XpackApiSuite
  extends WordSpec
    with BaseEsClusterIntegrationTest
    with SingleClientSupport
    with ESVersionSupport
    with BeforeAndAfterEach
    with Matchers {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/xpack_api/readonlyrest.yml"

  override lazy val targetEs = container.nodes.head

  override lazy val clusterContainer: EsClusterContainer = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      nodeDataInitializer = XpackApiSuite.nodeDataInitializer(),
      xPackSupport = true
    )
  )

  private lazy val adminIndexManager = new IndexManager(basicAuthClient("admin", "container"))
  private lazy val dev1SearchManager = new SearchManager(basicAuthClient("dev1", "test"))
  private lazy val dev2SearchManager = new SearchManager(basicAuthClient("dev2", "test"))
  private lazy val dev3IndexManager = new IndexManager(basicAuthClient("dev3", "test"))
  private lazy val dev4IndexManager = new IndexManager(basicAuthClient("dev4", "test"))
  private lazy val dev5IndexManager = new IndexManager(basicAuthClient("dev5", "test"))

  private lazy val adminSqlManager = new SqlApiManager(basicAuthClient("sqladmin", "pass"), container.esVersion)
  private lazy val dev3SqlManager = new SqlApiManager(basicAuthClient("dev1sql", "test"), container.esVersion)
  private lazy val dev4SqlManager = new SqlApiManager(basicAuthClient("dev2sql", "test"), container.esVersion)

  "Async search" should {
    "be allowed for dev1 and test1_index" excludeES(allEs5x, allEs6x, allEs7xBelowEs77x) in {
      val result = dev1SearchManager.asyncSearch("test1_index")

      result.responseCode should be (200)
      result.searchHits.map(i => i("_index").str).toSet should be(
        Set("test1_index")
      )
    }
    "not be allowed for dev2 and test1_index" excludeES(allEs5x, allEs6x, allEs7xBelowEs77x) in {
      val result = dev2SearchManager.asyncSearch("test1_index")

      result.responseCode should be (404)
    }
    "support filter and fields rule" excludeES(allEs5x, allEs6x, allEs7xBelowEs77x) in {
      val result = dev2SearchManager.asyncSearch("test2_index")

      result.responseCode should be (200)
      result.searchHits.map(i => i("_index").str).toSet should be(
        Set("test2_index")
      )
      result.searchHits.map(i => i("_source")).toSet should be(
        Set(ujson.read("""{"name":"john"}"""))
      )
    }
  }

  "Mustache lang" which {
    "Search can be done" when {
      "user uses local auth rule" when {
        "mustache template can be used" in {
          val searchManager = new SearchManager(basicAuthClient("dev1", "test"))
          val result = searchManager.searchTemplate(
            index = "test1_index",
            query = ujson.read(
              s"""
                 |{
                 |    "id": "template1",
                 |    "params": {
                 |        "query_string": "world"
                 |    }
                 |}""".stripMargin
            )
          )

          result.responseCode shouldEqual 200
          result.searchHits(0)("_source") should be(ujson.read("""{"hello":"world"}"""))
        }
      }
    }
    "Template rendering can be done" when {
      "user uses local auth rule" in {
        val searchManager = new SearchManager(basicAuthClient("dev1", "test"))

        val result = searchManager.renderTemplate(
          s"""
             |{
             |    "id": "template1",
             |    "params": {
             |        "query_string": "world"
             |    }
             |}
          """.stripMargin
        )

        result.responseCode shouldEqual 200
        result.body should be("""{"template_output":{"query":{"match":{"hello":"world"}}}}""")
      }
    }
  }

  "Rollup API" when {
    "create rollup job method is used" should {
      "be allowed to be used" when {
        "there is no indices rule defined" in {
          val jobName = NextRollupJobName.get
          val result = adminIndexManager.rollup(jobName, "test3*", "admin_t3")

          result.responseCode should be(200)
          val rollupJobsResult = adminIndexManager.getRollupJobs(jobName)
          rollupJobsResult.responseCode should be(200)
          rollupJobsResult.jobs.size should be(1)
        }
        "user has access to both: index pattern and rollup_index" in {
          val jobName = NextRollupJobName.get
          val result = dev3IndexManager.rollup(jobName, "test3*", "rollup_test3_job2")

          result.responseCode should be(200)
          val rollupJobsResult = adminIndexManager.getRollupJobs(jobName)
          rollupJobsResult.responseCode should be(200)
          rollupJobsResult.jobs.size should be(1)
        }
      }
      "not be allowed to be used" when {
        "user has no access to rollup_index" in {
          val result = dev3IndexManager.rollup(NextRollupJobName.get, "test3*", "rollup_index")

          result.responseCode should be(403)
        }
        "user has no access to passed index" in {
          val result = dev3IndexManager.rollup(NextRollupJobName.get, "test1_index", "rollup_index")

          result.responseCode should be(403)
        }
        "user has no access to given index pattern" in {
          val result = dev3IndexManager.rollup(NextRollupJobName.get, "test*", "rollup_index")

          result.responseCode should be(403)
        }
      }
    }
    "get rollup job capabilities method is used" should {
      "return non-empty list" when {
        "there is not indices rule defined" in {
          val jobName = NextRollupJobName.get
          adminIndexManager.rollup(jobName, "test4*", "admin_t4").force()

          val result = adminIndexManager.getRollupJobCapabilities("test4*")

          result.responseCode should be (200)
          val jobs = result.capabilities.values.toList.flatten
          jobs.map(_("job_id").str) should contain (jobName)
        }
        "user has access to requested indices" in {
          val jobName1 = NextRollupJobName.get
          val jobName2 = NextRollupJobName.get
          adminIndexManager.rollup(jobName1, "test4_index_a", s"rollup_test4_$jobName1").force()
          adminIndexManager.rollup(jobName2, "test3_index_a", s"rollup_test3_$jobName2").force()

          val result = dev4IndexManager.getRollupJobCapabilities("test4_index_a")

          result.responseCode should be (200)
          val jobs = result.capabilities.values.toList.flatten
          jobs.map(_("job_id").str) should contain (jobName1)
          jobs.foreach { job =>
            job("rollup_index").str should startWith ("rollup_test4")
            job("index_pattern").str should startWith ("test4")
          }
        }
        "user has access to requested index pattern" in {
          val jobName1 = NextRollupJobName.get
          val jobName2 = NextRollupJobName.get
          adminIndexManager.rollup(jobName1, "test4_index_a", s"rollup_test4_$jobName1").force()
          adminIndexManager.rollup(jobName2, "test3_index_a", s"rollup_test3_$jobName2").force()

          val result = dev4IndexManager.getRollupJobCapabilities("test4*")

          result.responseCode should be (200)
          val jobs = result.capabilities.values.toList.flatten
          jobs.map(_("job_id").str) should contain (jobName1)
          jobs.foreach { job =>
            job("rollup_index").str should startWith ("rollup_test4")
            job("index_pattern").str should startWith ("test4")
          }
        }
        "user has access to one index of requested index patten" in {
          val jobName1 = NextRollupJobName.get
          val jobName2 = NextRollupJobName.get
          adminIndexManager.rollup(jobName1, "test4_index_a", s"rollup_test4_$jobName1").force()
          adminIndexManager.rollup(jobName2, "test3_index_a", s"rollup_test3_$jobName2").force()

          val result = dev4IndexManager.getRollupJobCapabilities("test4*")

          result.responseCode should be (200)
          val jobs = result.capabilities.values.toList.flatten
          jobs.map(_("job_id").str) should contain (jobName1)
          jobs.foreach { job =>
            job("rollup_index").str should startWith ("rollup_test4")
            job("index_pattern").str should startWith ("test4")
          }
        }
      }
      "return empty list" when {
        "user has no access to requested index" in {
          val jobName = NextRollupJobName.get
          adminIndexManager.rollup(jobName, "test3_index_a", s"rollup_test3_$jobName").force()

          val result = dev4IndexManager.getRollupJobCapabilities("test3_index_a")

          result.responseCode should be (200)
          val jobs = result.capabilities.values.toList.flatten
          jobs.size should be (0)
        }
        "user had no access to requested index pattern" in {
          val jobName = NextRollupJobName.get
          adminIndexManager.rollup(jobName, "test4*", s"rollup_test4_$jobName").force()

          val result = dev4IndexManager.getRollupJobCapabilities("test3*")

          result.responseCode should be (200)
          val jobs = result.capabilities.values.toList.flatten
          jobs.size should be (0)
        }
      }
    }
    "get rollup index capabilities method is used" should {
      "return non-empty list" when {
        "there is not indices rule defined" in {
          val jobName = NextRollupJobName.get
          adminIndexManager.rollup(jobName, "test5*", "admin_t5").force()

          val result = adminIndexManager.getRollupIndexCapabilities("admin_t5")

          result.responseCode should be (200)
          val jobs = result.capabilities.values.toList.flatten
          jobs.map(_("job_id").str) should contain (jobName)
        }
        "user has access to requested indices" in {
          val jobName1 = NextRollupJobName.get
          val jobName2 = NextRollupJobName.get
          adminIndexManager.rollup(jobName1, "test5_index_a", s"rollup_test5_$jobName1").force()
          adminIndexManager.rollup(jobName2, "test3_index_a", s"rollup_test3_$jobName2").force()

          val result = dev5IndexManager.getRollupIndexCapabilities(s"rollup_test5_$jobName1")

          result.responseCode should be (200)
          val jobs = result.capabilities.values.toList.flatten
          jobs.map(_("job_id").str) should contain (jobName1)
          jobs.foreach { job =>
            job("rollup_index").str should startWith ("rollup_test5")
            job("index_pattern").str should startWith ("test5")
          }
        }
        "user has access to requested index pattern" in {
          val jobName1 = NextRollupJobName.get
          val jobName2 = NextRollupJobName.get
          adminIndexManager.rollup(jobName1, "test5_index_a", s"rollup_test5_$jobName1").force()
          adminIndexManager.rollup(jobName2, "test3_index_a", s"rollup_test3_$jobName2").force()

          val result = dev5IndexManager.getRollupIndexCapabilities("rollup_test5*")

          result.responseCode should be (200)
          val jobs = result.capabilities.values.toList.flatten
          jobs.map(_("job_id").str) should contain (jobName1)
          jobs.foreach { job =>
            job("rollup_index").str should startWith ("rollup_test5")
            job("index_pattern").str should startWith ("test5")
          }
        }
        "user has access to one index of requested index patten" in {
          val jobName1 = NextRollupJobName.get
          val jobName2 = NextRollupJobName.get
          adminIndexManager.rollup(jobName1, "test5_index_a", s"rollup_test5_$jobName1").force()
          adminIndexManager.rollup(jobName2, "test3_index_a", s"rollup_test3_$jobName2").force()

          val result = dev5IndexManager.getRollupIndexCapabilities("rollup_test*")

          result.responseCode should be (200)
          val jobs = result.capabilities.values.toList.flatten
          jobs.map(_("job_id").str) should contain (jobName1)
          jobs.foreach { job =>
            job("rollup_index").str should startWith ("rollup_test5")
            job("index_pattern").str should startWith ("test5")
          }
        }
      }
      "return empty list" when {
        "user had no access to requested index pattern" in {
          val jobName = NextRollupJobName.get
          adminIndexManager.rollup(jobName, "test5*", s"rollup_test5_$jobName").force()

          val result = dev5IndexManager.getRollupIndexCapabilities("rollup_test3*")

          result.responseCode should be (200)
          val jobs = result.capabilities.values.toList.flatten
          jobs.size should be (0)
        }
      }
      "return 404" when {
        "user has no access to requested index" in {
          val jobName = NextRollupJobName.get
          adminIndexManager.rollup(jobName, "test3_index_a", s"rollup_test3_$jobName").force()

          val result = dev5IndexManager.getRollupIndexCapabilities(s"rollup_test3_$jobName")

          result.responseCode should be (404)
        }
      }
    }
    "rollup search method is used" should {

    }
  }

  "SQL query request" when {
    "SELECT query is used" should {
      "be allowed" when {
        "user has no indices rule (has access to any index)" when {
          "full index name is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""SELECT * FROM library""")
            result.isSuccess should be(true)
            result.queryResult.size should be(4)
            result.columnNames should contain only("author", "internal_id", "name", "release_date")
            result.rows.size should be(2)
            result.column("author").toList should contain only(Str("James S.A. Corey"), Str("Dan Simmons"))
            result.column("internal_id").toList should contain only(Num(1), Num(2))
          }
          "full indices names are used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""SELECT * FROM \"bookstore,library\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(5)
            result.column("author").toList should contain only(
              Str("James S.A. Corey"), Str("Dan Simmons"), Str("Frank Herbert")
            )
            result.column("internal_id").toList should contain only(Num(1), Num(2), Null)
          }
          "wildcard is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""SELECT * FROM \"*\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(5)
            result.column("author").toList should contain only(
              Str("James S.A. Corey"), Str("Dan Simmons"), Str("Frank Herbert")
            )
            result.column("internal_id").toList should contain only(Num(1), Num(2), Null)
          }
          "alias is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
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
          "full index name is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = dev3SqlManager.execute("""SELECT * FROM bookstore""")
            result.isSuccess should be(true)
            result.queryResult.size should be(4)
            result.columnNames should contain only("author", "name", "price", "release_date")
            result.rows.size should be(3)
            result.column("author").toList should contain only(Str("James S.A. Corey"), Str("Dan Simmons"), Str("Frank Herbert"))
            result.column("price").toList should contain only Null
          }
          "full indices names are used and one of them is not allowed" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = dev3SqlManager.execute("""SELECT * FROM \"bookstore,library\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(4)
            result.columnNames should contain only("author", "name", "price", "release_date")
            result.rows.size should be(3)
            result.column("author").toList should contain only(Str("James S.A. Corey"), Str("Dan Simmons"), Str("Frank Herbert"))
            result.column("price").toList should contain only Null
          }
          "wildcard is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = dev3SqlManager.execute("""SELECT * FROM \"book*\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(4)
            result.columnNames should contain only("author", "name", "price", "release_date")
            result.rows.size should be(3)
            result.column("author").toList should contain only(Str("James S.A. Corey"), Str("Dan Simmons"), Str("Frank Herbert"))
            result.column("price").toList should contain only Null
          }
          "alias is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = dev3SqlManager.execute("""SELECT * FROM \"bookshop\"""")
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
          "full index name is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = dev4SqlManager.execute("""SELECT * FROM bookstore""")
            result.isBadRequest should be(true)
            result.responseJson("error").obj("reason").str should include("Unknown index")
          }
          "wildcard is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = dev4SqlManager.execute("""SELECT * FROM \"book*\"""")
            result.isBadRequest should be(true)
            result.responseJson("error").obj("reason").str should include("Unknown index")
          }
          "alias is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = dev4SqlManager.execute("""SELECT * FROM bookshop""")
            result.isBadRequest should be(true)
            result.responseJson("error").obj("reason").str should include("Unknown index")
          }
          "not-existent index name is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = dev4SqlManager.execute("""SELECT * FROM flea_market""")
            result.isBadRequest should be(true)
            result.responseJson("error").obj("reason").str should include("Unknown index")
          }
        }
      }
      "be malformed" when {
        "user rule is not used" when {
          "not-existent index name is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
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
          "full index name is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""DESCRIBE library""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should contain only(
              "author", "author.keyword", "internal_id", "name", "name.keyword", "release_date"
            )
          }
          "full indices names are used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""DESCRIBE \"bookstore,library\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should contain only(
              "author", "author.keyword", "internal_id", "name", "name.keyword", "price", "release_date"
            )
          }
          "wildcard is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""DESCRIBE \"*\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should contain only(
              "author", "author.keyword", "internal_id", "name", "name.keyword", "price", "release_date"
            )
          }
          "alias is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""DESCRIBE bookshop""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should contain only(
              "author", "author.keyword", "name", "name.keyword", "price", "release_date"
            )
          }
          "not-existent index name is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""DESCRIBE unknown""")
            result.isSuccess should be(true)
            result.queryResult.size should be(0)
          }
        }
        "user has access to given index" when {
          "full index name is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = dev3SqlManager.execute("""DESCRIBE bookstore""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should contain only(
              "author", "author.keyword", "name", "name.keyword", "price", "release_date"
            )
          }
          "full indices names are used and one of them is not allowed" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = dev3SqlManager.execute("""DESCRIBE \"bookstore,library\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should contain only(
              "author", "author.keyword", "name", "name.keyword", "price", "release_date"
            )
          }
          "wildcard is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = dev3SqlManager.execute("""DESCRIBE \"*\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should contain only(
              "author", "author.keyword", "name", "name.keyword", "price", "release_date"
            )
          }
          "alias is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = dev3SqlManager.execute("""DESCRIBE \"bookshop\"""")
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
          "full index name is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = dev4SqlManager.execute("""DESCRIBE bookstore""")
            result.isSuccess should be(true)
            result.queryResult.size should be(0)
          }
          "wildcard is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = dev4SqlManager.execute("""DESCRIBE \"book*\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(0)
          }
          "alias is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = dev4SqlManager.execute("""DESCRIBE bookshop""")
            result.isSuccess should be(true)
            result.queryResult.size should be(0)
          }
          "not-existent index name is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = dev4SqlManager.execute("""DESCRIBE flea_market""")
            result.isSuccess should be(true)
            result.queryResult.size should be(0)
          }
        }
      }
    }
    "SHOW COLUMNS command is used" should {
      "be allowed" when {
        "user has no indices rule (has access to any index)" when {
          "full index name is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""SHOW COLUMNS IN library""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should contain only(
              "author", "author.keyword", "internal_id", "name", "name.keyword", "release_date"
            )
          }
          "full indices names are used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""SHOW COLUMNS IN \"bookstore,library\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should contain only(
              "author", "author.keyword", "internal_id", "name", "name.keyword", "price", "release_date"
            )
          }
          "wildcard is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""SHOW COLUMNS IN \"*\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should contain only(
              "author", "author.keyword", "internal_id", "name", "name.keyword", "price", "release_date"
            )
          }
          "alias is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""SHOW COLUMNS IN bookshop""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should contain only(
              "author", "author.keyword", "name", "name.keyword", "price", "release_date"
            )
          }
          "not-existent index name is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""SHOW COLUMNS IN unknown""")
            result.isSuccess should be(true)
            result.queryResult.size should be(0)
          }
        }
        "user has access to given index" when {
          "full index name is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = dev3SqlManager.execute("""SHOW COLUMNS IN bookstore""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should contain only(
              "author", "author.keyword", "name", "name.keyword", "price", "release_date"
            )
          }
          "full indices names are used and one of them is not allowed" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = dev3SqlManager.execute("""SHOW COLUMNS FROM \"bookstore,library\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should contain only(
              "author", "author.keyword", "name", "name.keyword", "price", "release_date"
            )
          }
          "wildcard is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = dev3SqlManager.execute("""SHOW COLUMNS FROM \"*\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(3)
            result.column("column").map(_.str) should contain only(
              "author", "author.keyword", "name", "name.keyword", "price", "release_date"
            )
          }
          "alias is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = dev3SqlManager.execute("""SHOW COLUMNS FROM \"bookshop\"""")
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
          "full index name is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = dev4SqlManager.execute("""SHOW COLUMNS FROM bookstore""")
            result.isSuccess should be(true)
            result.queryResult.size should be(0)
          }
          "wildcard is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = dev4SqlManager.execute("""SHOW COLUMNS FROM \"book*\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(0)
          }
          "alias is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = dev4SqlManager.execute("""SHOW COLUMNS FROM bookshop""")
            result.isSuccess should be(true)
            result.queryResult.size should be(0)
          }
          "not-existent index name is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = dev4SqlManager.execute("""SHOW COLUMNS FROM flea_market""")
            result.isSuccess should be(true)
            result.queryResult.size should be(0)
          }
        }
      }
    }
    "SHOW TABLES command is used" should {
      "be allowed" when {
        "user has no indices rule (has access to any index)" when {
          "full index name is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""SHOW TABLES library""")
            result.isSuccess should be(true)
            result.column("name").map(_.str).headOption should be(Some("library"))
          }
          "full indices names are used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""SHOW TABLES \"bookstore,library\"""")
            result.isSuccess should be(true)
            result.column("name").map(_.str) should contain only("bookstore", "library")
          }
          "wildcard is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""SHOW TABLES \"*\"""")
            result.isSuccess should be(true)
            result.column("name").map(_.str) should contain only("bookshop", "bookstore", "library")
          }
          "all tables are requested" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""SHOW TABLES""")
            result.isSuccess should be(true)
            result.column("name").map(_.str) should contain only("bookshop", "bookstore", "library")
          }
          "alias is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""SHOW TABLES bookshop""")
            result.isSuccess should be(true)
            result.column("name").map(_.str).headOption should be(Some("bookshop"))
          }
          "not-existent index name is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = adminSqlManager.execute("""SHOW TABLES unknown""")
            result.isSuccess should be(true)
            result.queryResult.size should be(0)
          }
        }
        "user has access to given index" when {
          "full index name is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = dev3SqlManager.execute("""SHOW TABLES bookstore""")
            result.isSuccess should be(true)
            result.column("name").map(_.str).headOption should be(Some("bookstore"))
          }
          "full indices names are used and one of them is not allowed" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = dev3SqlManager.execute("""SHOW TABLES \"bookstore,library\"""")
            result.isSuccess should be(true)
            result.column("name").map(_.str).headOption should be(Some("bookstore"))
          }
          "wildcard is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = dev3SqlManager.execute("""SHOW TABLES \"*\"""")
            result.isSuccess should be(true)
            result.column("name").map(_.str).headOption should be(Some("bookstore"))
          }
          "all tables are requested" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = dev3SqlManager.execute("""SHOW TABLES""")
            result.isSuccess should be(true)
            result.column("name").map(_.str).headOption should be(Some("bookstore"))
          }
          "alias is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = dev3SqlManager.execute("""SHOW TABLES \"bookshop\"""")
            result.isSuccess should be(true)
            result.column("name").map(_.str).headOption should be(Some("bookstore"))
          }
        }
      }
      "be forbidden" when {
        "user doesn't have access to given index" when {
          "full index name is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = dev4SqlManager.execute("""SHOW TABLES bookstore""")
            result.isSuccess should be(true)
            result.queryResult.size should be(0)
          }
          "wildcard is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = dev4SqlManager.execute("""SHOW TABLES \"book*\"""")
            result.isSuccess should be(true)
            result.queryResult.size should be(0)
          }
          "alias is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = dev4SqlManager.execute("""SHOW TABLES bookshop""")
            result.isSuccess should be(true)
            result.queryResult.size should be(0)
          }
          "not-existent index name is used" excludeES("es55x", "es60x", "es61x", "es62x") in {
            val result = dev4SqlManager.execute("""SHOW TABLES flea_market""")
            result.isSuccess should be(true)
            result.queryResult.size should be(0)
          }
        }
      }
    }
    "SHOW FUNCTIONS command is used" should {
      "be allowed" when {
        "user has no indices rule (has access to any index)" excludeES("es55x", "es60x", "es61x", "es62x") in {
          val result = adminSqlManager.execute("""SHOW FUNCTIONS""")
          result.isSuccess should be(true)
        }
        "user has one index" excludeES("es55x", "es60x", "es61x", "es62x") in {
          val result = dev4SqlManager.execute("""SHOW FUNCTIONS""")
          result.isSuccess should be(true)
        }
      }
    }
  }
}

object XpackApiSuite {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    val indexManager = new IndexManager(adminRestClient)

    createDocs(documentManager)
    storeScriptTemplate(adminRestClient)
    configureBookstore(documentManager, indexManager)
    configureLibrary(documentManager)
  }

  private def createDocs(documentManager: DocumentManager): Unit = {
    documentManager.createDoc("test1_index", 1, ujson.read("""{"hello":"world"}""")).force()

    documentManager.createDoc("test2_index", 1, ujson.read("""{"name":"john", "age":33}""")).force()
    documentManager.createDoc("test2_index", 2, ujson.read("""{"name":"bill", "age":50}""")).force()

    documentManager.createDoc("test3_index_a", 1, ujson.read("""{"timestamp":"2020-01-01", "counter": 10}""")).force()
    documentManager.createDoc("test3_index_b", 1, ujson.read("""{"timestamp":"2020-02-01", "counter": 100}""")).force()

    documentManager.createDoc("test4_index_a", 1, ujson.read("""{"timestamp":"2020-01-01", "counter": 10}""")).force()
    documentManager.createDoc("test4_index_b", 1, ujson.read("""{"timestamp":"2020-02-01", "counter": 100}""")).force()

    documentManager.createDoc("test5_index_a", 1, ujson.read("""{"timestamp":"2020-01-01", "counter": 10}""")).force()
    documentManager.createDoc("test5_index_b", 1, ujson.read("""{"timestamp":"2020-02-01", "counter": 100}""")).force()
  }

  private def storeScriptTemplate(adminRestClient: RestClient): Unit = {
    val scriptManager = new ScriptManager(adminRestClient)
    val script =
      """
        |{
        |    "script": {
        |        "lang": "mustache",
        |        "source": {
        |            "query": {
        |                "match": {
        |                    "hello": "{{query_string}}"
        |                }
        |            }
        |        }
        |    }
        |}
      """.stripMargin
    scriptManager.store(s"/_scripts/template1", script).force()
  }

  private def configureBookstore(documentManager: DocumentManager, indexManager: IndexManager): Unit = {
    documentManager.createDocAndAssert("bookstore", "stock", 1, ujson.read(
      s"""{"name": "Leviathan Wakes", "author": "James S.A. Corey", "release_date": "2011-06-02", "price": 100}"""
    ))
    documentManager.createDocAndAssert("bookstore", "stock", 2, ujson.read(
      s"""{"name": "Hyperion", "author": "Dan Simmons", "release_date": "1989-05-26", "price": 200}"""
    ))
    documentManager.createDocAndAssert("bookstore", "stock", 3, ujson.read(
      s"""{"name": "Dune", "author": "Frank Herbert", "release_date": "1965-06-01", "price": 50}"""
    ))
    indexManager.createAliasOf("bookstore", "bookshop").force()
  }

  private def configureLibrary(documentManager: DocumentManager): Unit = {
    documentManager.createDocAndAssert("library", "book", 1, ujson.read(
      s"""{"name": "Leviathan Wakes", "author": "James S.A. Corey", "release_date": "2011-06-02", "internal_id": 1}"""
    ))
    documentManager.createDocAndAssert("library", "book", 2, ujson.read(
      s"""{"name": "Hyperion", "author": "Dan Simmons", "release_date": "1989-05-26", "internal_id": 2}"""
    ))
  }

  private object NextRollupJobName {
    private val currentId = Atomic(0)
    def get: String = s"job${currentId.incrementAndGet()}"
  }
}