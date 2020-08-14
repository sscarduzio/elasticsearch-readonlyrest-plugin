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

import org.scalatest.{Matchers, WordSpec}
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.integration.utils.ESVersionSupport
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsClusterContainer, EsClusterSettings, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, IndexManager, ScriptManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait XpackApiSuite
  extends WordSpec
    with BaseEsClusterIntegrationTest
    with SingleClientSupport
    with ESVersionSupport
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

      result.responseCode should be (401)
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
        "there it no indices rule defined" in {
          val result = adminIndexManager.rollup("job1", "test3*", "admin")

          result.responseCode should be(200)
          val rollupJobsResult = adminIndexManager.getRollupJobs("job1")
          rollupJobsResult.responseCode should be(200)
          rollupJobsResult.jobs.size should be(1)
        }
        "user has access to both: index pattern and rollup_index" in {
          val result = dev3IndexManager.rollup("job2", "test3*", "test3_rollup_job2")

          result.responseCode should be(200)
          val rollupJobsResult = adminIndexManager.getRollupJobs("job2")
          rollupJobsResult.responseCode should be(200)
          rollupJobsResult.jobs.size should be(1)
        }
      }
      "not be allowed to be used" when {
        "user has no access to rollup_index" in {
          val result = dev3IndexManager.rollup("job3", "test3*", "rollup_index")

          result.responseCode should be(401)
        }
        "user has no access to passed index" in {
          val result = dev3IndexManager.rollup("job4", "test1_index", "rollup_index")

          result.responseCode should be(401)
        }
        "user has no access to given index pattern" in {
          val result = dev3IndexManager.rollup("job5", "test*", "rollup_index")

          result.responseCode should be(401)
        }
      }
    }
    "get rollup capabilities method is used" should {

    }
    "get index capabilities method is used" should {

    }
    "rollup search method is used" should {

    }
  }
}

object XpackApiSuite {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    createDocs(adminRestClient, esVersion)
    storeScriptTemplate(adminRestClient)
  }

  private def createDocs(adminRestClient: RestClient, esVersion: String): Unit = {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    documentManager.createDoc("test1_index", 1, ujson.read("""{"hello":"world"}""")).force()

    documentManager.createDoc("test2_index", 1, ujson.read("""{"name":"john", "age":33}""")).force()
    documentManager.createDoc("test2_index", 2, ujson.read("""{"name":"bill", "age":50}""")).force()

    documentManager.createDoc("test3_index_a", 1, ujson.read("""{"timestamp":"2020-01-01", "counter": 10}""")).force()
    documentManager.createDoc("test3_index_b", 1, ujson.read("""{"timestamp":"2020-02-01", "counter": 100}""")).force()
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
}