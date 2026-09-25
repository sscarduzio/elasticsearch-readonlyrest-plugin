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

import monix.execution.atomic.Atomic
import org.scalatest.wordspec.AnyWordSpecLike
import tech.beshu.ror.integration.suites.base.BaseXpackApiSuite.{NextRollupJobName, nodeDataInitializer}
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyWordSpecLike, PluginTestSupport}
import tech.beshu.ror.utils.TestUjson.ujson
import tech.beshu.ror.utils.containers.*
import tech.beshu.ror.utils.elasticsearch.*
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers

trait BaseXpackApiSuite
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
      nodeDataInitializer = new ComposedElasticsearchNodeDataInitializer(
        nodeDataInitializer(),
        implementationSpecificInitializer()
      ),
      securityType = rorClusterSecurityType
    )
  )

  protected def implementationSpecificInitializer(): ElasticsearchNodeDataInitializer =
    NoOpElasticsearchNodeDataInitializer

  protected lazy val adminXpackApiManager = new XpackApiManager(adminClient, esVersionUsed)
  private lazy val dev1SearchManager = new SearchManager(basicAuthClient("dev1", "test"), esVersionUsed)
  private lazy val dev2SearchManager = new SearchManager(basicAuthClient("dev2", "test"), esVersionUsed)
  private lazy val dev3SearchManager = new SearchManager(basicAuthClient("dev3", "test"), esVersionUsed)
  private lazy val dev1XpackApiManager = new XpackApiManager(basicAuthClient("dev1", "test"), esVersionUsed)
  private lazy val dev2XpackApiManager = new XpackApiManager(basicAuthClient("dev2", "test"), esVersionUsed)
  private lazy val dev3XpackApiManager = new XpackApiManager(basicAuthClient("dev3", "test"), esVersionUsed)
  private lazy val dev4XpackApiManager = new XpackApiManager(basicAuthClient("dev4", "test"), esVersionUsed)
  private lazy val dev5XpackApiManager = new XpackApiManager(basicAuthClient("dev5", "test"), esVersionUsed)
  private lazy val dev6XpackApiManager = new XpackApiManager(basicAuthClient("dev6", "test"), esVersionUsed)
  private lazy val dev8XpackApiManager = new XpackApiManager(basicAuthClient("dev8", "test"), esVersionUsed)
  private lazy val dev9XpackApiManager = new XpackApiManager(basicAuthClient("dev9", "test"), esVersionUsed)

  "Async search" should {
    "be allowed for dev1 and test1_index_a" excludeES (allEs6x, allEs7xBelowEs77x) in {
      val result = dev1SearchManager.asyncSearch("test1_index_a")

      result should have statusCode 200
      result.searchHits.map(i => i("_index").str).toSet should be(
        Set("test1_index_a")
      )
    }
    "not be allowed for dev2 and test1_index_a" excludeES (allEs6x, allEs7xBelowEs77x) in {
      val result = dev2SearchManager.asyncSearch("test1_index_a")

      result should have statusCode 404
    }
    "support filter and fields rule" excludeES (allEs6x, allEs7xBelowEs77x) in {
      val result = dev2SearchManager.asyncSearch("test2_index")

      result should have statusCode 200
      result.searchHits.map(i => i("_index").str).toSet should be(
        Set("test2_index")
      )
      result.searchHits.map(i => i("_source")).toSet should be(
        Set(ujson.read("""{"name":"john"}"""))
      )
    }
    "not be called for closed indices" excludeES (allEs6x, allEs7xBelowEs77x) in {
      val result = dev3SearchManager.asyncSearch("test3*")

      result should have statusCode 200
      result.searchHits.map(i => i("_index").str).toSet should be(
        Set("test3_index_a", "test3_index_b")
      )
    }
    "status is properly handled" excludeES (allEs6x, allEs7xBelowEs77x) in {
      val notExistingSearchId = "FmRldE8zREVEUzA2ZVpUeGs2ejJFUFEaMkZ5QTVrSTZSaVN3WlNFVmtlWHJsdzoxMDc="
      val result = dev1SearchManager.asyncSearchStatus(notExistingSearchId)

      result should have statusCode 404
    }
  }

  "Mustache lang" when {
    "search template is used" should {
      "return only indices which user has an access to" in {
        val searchManager = new SearchManager(basicAuthClient("dev1", "test"), esVersionUsed)
        val result = searchManager.searchTemplate(
          index = "test1_index*",
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

        result should have statusCode 200
        result.searchHits.map(_("_index").str).distinct should be(List("test1_index_a"))
        result.searchHits.map(_("_source")) should be(List(ujson.read("""{"hello":"world"}""")))
      }
      "return empty response for dev3" in {
        val searchManager = new SearchManager(basicAuthClient("dev3", "test"), esVersionUsed)
        val result = searchManager.searchTemplate(
          index = "test1_index*",
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

        result should have statusCode 200
        result.searchHits.map(_("_index").str).distinct should be(List.empty)
        result.searchHits.map(_("_source")) should be(List.empty)
      }
      "return filtered documents" in {
        val searchManager = new SearchManager(basicAuthClient("dev7", "test"), esVersionUsed)
        val result = searchManager.searchTemplate(
          index = "test7_index",
          query = ujson.read(
            """
              |{
              |  "source": {
              |    "query": {
              |      "bool": {
              |        "filter": [
              |          {
              |            "query_string": {
              |              "query": "a1 OR a2 OR a3",
              |              "fields": [
              |                "content.app.keyword"
              |              ],
              |              "default_operator": "OR",
              |              "analyze_wildcard": false
              |            }
              |          }
              |        ]
              |      }
              |    }
              |  }
              |}""".stripMargin
          )
        )

        result should have statusCode 200
        result.searchHits.map(_("_index").str).distinct should be(List("test7_index"))
        result.searchHits.map(_("_source")) should be(List(ujson.read("""{"content":{ "app": "a1" }}""")))
      }
    }
    "multisearch template is used" should {
      "return only indices which user has an access to" in {
        val searchManager = new SearchManager(basicAuthClient("dev1", "test"), esVersionUsed)
        val result = searchManager.mSearchTemplate(
          ujson.read("""{"index":"test1_index*"}"""),
          ujson.read(
            s"""
               |{
               |    "id": "template1",
               |    "params": {
               |        "query_string": "world"
               |    }
               |}""".stripMargin
          )
        )

        result should have statusCode 200
        result.responseJson("responses").arr.size should be(1)
        val firstQueryResponse = result.responseJson("responses")(0)
        firstQueryResponse("hits")("hits").arr.map(_("_index").str).distinct should be(List("test1_index_a"))
        firstQueryResponse("hits")("hits").arr.map(_("_source")) should be(List(ujson.read("""{"hello":"world"}""")))
      }
      "return empty response for dev3" in {
        val searchManager = new SearchManager(basicAuthClient("dev3", "test"), esVersionUsed)
        val result = searchManager.mSearchTemplate(
          ujson.read("""{"index":"test1_index*"}"""),
          ujson.read(
            s"""
               |{
               |    "id": "template1",
               |    "params": {
               |        "query_string": "world"
               |    }
               |}""".stripMargin
          )
        )

        result should have statusCode 200
        result.responseJson("responses").arr.size should be(1)
        val firstQueryResponse = result.responseJson("responses")(0)
        firstQueryResponse("hits")("hits").arr.map(_("_index").str).distinct should be(List.empty)
        firstQueryResponse("hits")("hits").arr.map(_("_source")) should be(List.empty)
      }
      "return filtered documents" in {
        val searchManager = new SearchManager(basicAuthClient("dev7", "test"), esVersionUsed)
        val result = searchManager.mSearchTemplate(
          ujson.read("""{"index":"test7_index"}"""),
          ujson.read(
            """
              |{
              |  "source": {
              |    "query": {
              |      "bool": {
              |        "filter": [
              |          {
              |            "query_string": {
              |              "query": "a1 OR a2 OR a3",
              |              "fields": [
              |                "content.app.keyword"
              |              ],
              |              "default_operator": "OR",
              |              "analyze_wildcard": false
              |            }
              |          }
              |        ]
              |      }
              |    }
              |  }
              |}""".stripMargin
          )
        )

        result should have statusCode 200
        result.responseJson("responses").arr.size should be(1)
        val firstQueryResponse = result.responseJson("responses")(0)
        firstQueryResponse("hits")("hits").arr.map(_("_index").str).distinct should be(List("test7_index"))
        firstQueryResponse("hits")("hits").arr.map(_("_source")) should be(
          List(ujson.read("""{"content":{ "app": "a1" }}"""))
        )
      }
    }
    "render template is used" should {
      "be allowed to be used for dev1" in {
        val searchManager = new SearchManager(basicAuthClient("dev1", "test"), esVersionUsed)

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

        result should have statusCode 200
        result.body should be("""{"template_output":{"query":{"match":{"hello":"world"}}}}""")
      }
    }
  }

  "Rollup API" when {
    "create rollup job method is used" should {
      "be allowed to be used" when {
        "there is no indices rule defined" excludeES (allES8xAboveEs815x, allEs9x) in {
          val jobName = NextRollupJobName.get
          val result = adminXpackApiManager.rollup(jobName, "test3*", "admin_t3")

          result should have statusCode 200
          val rollupJobsResult = adminXpackApiManager.getRollupJobs(jobName)
          rollupJobsResult should have statusCode 200
          rollupJobsResult.jobs.size should be(1)
        }
        "user has access to both: index pattern and rollup_index" excludeES (allES8xAboveEs815x, allEs9x) in {
          val jobName = NextRollupJobName.get
          val result = dev3XpackApiManager.rollup(jobName, "test3*", s"rollup_test3_$jobName")

          result should have statusCode 200
          val rollupJobsResult = adminXpackApiManager.getRollupJobs(jobName)
          rollupJobsResult should have statusCode 200
          rollupJobsResult.jobs.size should be(1)
        }
      }
      "not be allowed to be used" when {
        "user has no access to rollup_index" excludeES (allES8xAboveEs815x, allEs9x) in {
          val result = dev3XpackApiManager.rollup(NextRollupJobName.get, "test3*", "rollup_index")

          result should have statusCode 403
        }
        "user has no access to passed index" excludeES (allES8xAboveEs815x, allEs9x) in {
          val result = dev3XpackApiManager.rollup(NextRollupJobName.get, "test1_index", "rollup_index")

          result should have statusCode 403
        }
        "user has no access to given index pattern" excludeES (allES8xAboveEs815x, allEs9x) in {
          val result = dev3XpackApiManager.rollup(NextRollupJobName.get, "test*", "rollup_index")

          result should have statusCode 403
        }
      }
    }
    "get rollup job capabilities method is used" should {
      "return non-empty list" when {
        "there is not indices rule defined" excludeES (allES8xAboveEs815x, allEs9x) in {
          val jobName = NextRollupJobName.get
          adminXpackApiManager.rollup(jobName, "test4*", "admin_t4").force()

          val result = adminXpackApiManager.getRollupJobCapabilities("test4*")

          result should have statusCode 200
          val jobs = result.capabilities.values.toList.flatten
          jobs.map(_("job_id").str) should contain(jobName)
        }
        "user has access to requested indices" excludeES (allES8xAboveEs815x, allEs9x) in {
          val jobName1 = NextRollupJobName.get
          val jobName2 = NextRollupJobName.get
          adminXpackApiManager.rollup(jobName1, "test4_index_a", s"rollup_test4_$jobName1").force()
          adminXpackApiManager.rollup(jobName2, "test3_index_a", s"rollup_test3_$jobName2").force()

          val result = dev4XpackApiManager.getRollupJobCapabilities("test4_index_a")

          result should have statusCode 200
          val jobs = result.capabilities.values.toList.flatten
          jobs.map(_("job_id").str) should contain(jobName1)
          jobs.foreach { job =>
            job("rollup_index").str should startWith("rollup_test4")
            job("index_pattern").str should startWith("test4")
          }
        }
        "user has access to requested index pattern" excludeES (allES8xAboveEs815x, allEs9x) in {
          val jobName1 = NextRollupJobName.get
          val jobName2 = NextRollupJobName.get
          val jobName3 = NextRollupJobName.get
          adminXpackApiManager.rollup(jobName1, "test4_index_a", s"rollup_test4_$jobName1").force()
          adminXpackApiManager.rollup(jobName2, "test4_index_b", s"rollup_test4_$jobName2").force()
          adminXpackApiManager.rollup(jobName3, "test3_index_a", s"rollup_test3_$jobName3").force()

          val result = dev4XpackApiManager.getRollupJobCapabilities("test4*")

          result should have statusCode 200
          val jobs = result.capabilities.values.toList.flatten
          jobs.map(_("job_id").str) should contain oneOf (jobName1, jobName2)
          jobs.map(_("job_id").str) should not contain jobName3
          jobs.foreach { job =>
            job("rollup_index").str should startWith("rollup_test4")
            job("index_pattern").str should startWith("test4")
          }
        }
        "user has access to one index of requested index pattern" excludeES (allES8xAboveEs815x, allEs9x) in {
          val jobName1 = NextRollupJobName.get
          val jobName2 = NextRollupJobName.get
          val jobName3 = NextRollupJobName.get
          adminXpackApiManager.rollup(jobName1, "test4_index_a", s"rollup_test4_$jobName1").force()
          adminXpackApiManager.rollup(jobName2, "test4_index_b", s"rollup_test4_$jobName2").force()
          adminXpackApiManager.rollup(jobName3, "test3_index_a", s"rollup_test3_$jobName3").force()

          val result = dev4XpackApiManager.getRollupJobCapabilities("test4*")

          result should have statusCode 200
          val jobs = result.capabilities.values.toList.flatten
          jobs.map(_("job_id").str) should contain oneOf (jobName1, jobName2)
          jobs.map(_("job_id").str) should not contain jobName3
          jobs.foreach { job =>
            job("rollup_index").str should startWith("rollup_test4")
            job("index_pattern").str should startWith("test4")
          }
        }
      }
      "return empty list" when {
        "user has no access to requested index" excludeES (allES8xAboveEs815x, allEs9x) in {
          val jobName = NextRollupJobName.get
          adminXpackApiManager.rollup(jobName, "test3_index_a", s"rollup_test3_$jobName").force()

          val result = dev4XpackApiManager.getRollupJobCapabilities("test3_index_a")

          result should have statusCode 200
          val jobs = result.capabilities.values.toList.flatten
          jobs.size should be(0)
        }
        "user had no access to requested index pattern" excludeES (allES8xAboveEs815x, allEs9x) in {
          val jobName = NextRollupJobName.get
          adminXpackApiManager.rollup(jobName, "test4*", s"rollup_test4_$jobName").force()

          val result = dev4XpackApiManager.getRollupJobCapabilities("test3*")

          result should have statusCode 200
          val jobs = result.capabilities.values.toList.flatten
          jobs.size should be(0)
        }
      }
    }
    "get rollup index capabilities method is used" should {
      "return non-empty list" when {
        "there is not indices rule defined" excludeES (allES8xAboveEs815x, allEs9x) in {
          val jobName = NextRollupJobName.get
          adminXpackApiManager.rollup(jobName, "test5*", "admin_t5").force()

          val result = adminXpackApiManager.getRollupIndexCapabilities("admin_t5")

          result should have statusCode 200
          val jobs = result.capabilities.values.toList.flatten
          jobs.map(_("job_id").str) should contain(jobName)
        }
        "user has access to requested indices" excludeES (allES8xAboveEs815x, allEs9x) in {
          val jobName1 = NextRollupJobName.get
          val jobName2 = NextRollupJobName.get
          adminXpackApiManager.rollup(jobName1, "test5_index_a", s"rollup_test5_$jobName1").force()
          adminXpackApiManager.rollup(jobName2, "test3_index_a", s"rollup_test3_$jobName2").force()

          val result = dev5XpackApiManager.getRollupIndexCapabilities(s"rollup_test5_$jobName1")

          result should have statusCode 200
          val jobs = result.capabilities.values.toList.flatten
          jobs.map(_("job_id").str) should contain(jobName1)
          jobs.foreach { job =>
            job("rollup_index").str should startWith("rollup_test5")
            job("index_pattern").str should startWith("test5")
          }
        }
        "user has access to requested index pattern" excludeES (allES8xAboveEs815x, allEs9x) in {
          val jobName1 = NextRollupJobName.get
          val jobName2 = NextRollupJobName.get
          adminXpackApiManager.rollup(jobName1, "test5_index_a", s"rollup_test5_$jobName1").force()
          adminXpackApiManager.rollup(jobName2, "test3_index_a", s"rollup_test3_$jobName2").force()

          val result = dev5XpackApiManager.getRollupIndexCapabilities("rollup_test5*")

          result should have statusCode 200
          val jobs = result.capabilities.values.toList.flatten
          jobs.map(_("job_id").str) should contain(jobName1)
          jobs.foreach { job =>
            job("rollup_index").str should startWith("rollup_test5")
            job("index_pattern").str should startWith("test5")
          }
        }
        "user has access to one index of requested index patten" excludeES (allES8xAboveEs815x, allEs9x) in {
          val jobName1 = NextRollupJobName.get
          val jobName2 = NextRollupJobName.get
          adminXpackApiManager.rollup(jobName1, "test5_index_a", s"rollup_test5_$jobName1").force()
          adminXpackApiManager.rollup(jobName2, "test3_index_a", s"rollup_test3_$jobName2").force()

          val result = dev5XpackApiManager.getRollupIndexCapabilities("rollup_test*")

          result should have statusCode 200
          val jobs = result.capabilities.values.toList.flatten
          jobs.map(_("job_id").str) should contain(jobName1)
          jobs.foreach { job =>
            job("rollup_index").str should startWith("rollup_test5")
            job("index_pattern").str should startWith("test5")
          }
        }
      }
      "return empty list" when {
        "user had no access to requested index pattern" excludeES (allES8xAboveEs815x, allEs9x) in {
          val jobName = NextRollupJobName.get
          adminXpackApiManager.rollup(jobName, "test5*", s"rollup_test5_$jobName").force()

          val result = dev5XpackApiManager.getRollupIndexCapabilities("rollup_test3*")

          result should have statusCode 200
          val jobs = result.capabilities.values.toList.flatten
          jobs.size should be(0)
        }
      }
      "return 404" when {
        "user has no access to requested index" excludeES (allES8xAboveEs815x, allEs9x) in {
          val jobName = NextRollupJobName.get
          adminXpackApiManager.rollup(jobName, "test3_index_a", s"rollup_test3_$jobName").force()

          val result = dev5XpackApiManager.getRollupIndexCapabilities(s"rollup_test3_$jobName")

          result should have statusCode 404
        }
      }
    }
    "rollup search method is used" should {
      "be allowed to be used" when {
        "user has access to called rollup index" excludeES (allES8xAboveEs815x, allEs9x) in {
          val jobName1 = NextRollupJobName.get
          val jobName2 = NextRollupJobName.get
          val rollupIndex6a = s"rollup_test6_$jobName1"
          adminXpackApiManager.rollup(jobName1, "test6_index_a", rollupIndex6a).force()
          adminXpackApiManager.rollup(jobName2, "test6_index_b", s"rollup_test6_$jobName2").force()

          val result = dev6XpackApiManager.rollupSearch(rollupIndex6a)

          result should have statusCode 200
        }
      }
      "return 404" when {
        "user has no access to called rollup index" excludeES (allES8xAboveEs815x, allEs9x) in {
          val jobName = NextRollupJobName.get
          val rollupIndex = s"rollup_test4_$jobName"
          adminXpackApiManager.rollup(jobName, "test4*", rollupIndex).force()

          val result = dev6XpackApiManager.rollupSearch(rollupIndex)

          result should have statusCode 404
        }
      }
    }
  }

  "Get terms request" should {
    "be allowed for dev1 and test1_index_a" excludeES (allEs6x, allEs7xBelowEs714x) in {
      val result = dev1XpackApiManager.getTerms("test1_index_a", "hello.keyword")

      result should have statusCode 200
      result.terms should be(Set("world"))
    }
    "not be allowed for dev2 and test1_index_a" excludeES (allEs6x, allEs7xBelowEs714x) in {
      val result = dev2XpackApiManager.getTerms("test1_index_a", "hello.keyword")

      result should have statusCode 404
    }
    "be forbidden for dev2 and test2_index because of the filter rule" excludeES (allEs6x, allEs7xBelowEs714x) in {
      val result = dev2XpackApiManager.getTerms("test2_index", "age.keyword")

      result should have statusCode 403
    }
    "return terms for a field allowed by the fields whitelist" excludeES (allEs6x, allEs7xBelowEs714x) in {
      val result = dev8XpackApiManager.getTerms("test2_index", "name.keyword")

      result should have statusCode 200
      result.terms should be(Set("bill", "john"))
    }
    "return empty result for a field not covered by the fields whitelist" excludeES (allEs6x, allEs7xBelowEs714x) in {
      val result = dev8XpackApiManager.getTerms("test2_index", "age.keyword")

      result should have statusCode 200
      result.terms should be(Set.empty)
    }
    "return terms for a field not covered by the fields blacklist" excludeES (allEs6x, allEs7xBelowEs714x) in {
      val result = dev9XpackApiManager.getTerms("test2_index", "name.keyword")

      result should have statusCode 200
      result.terms should be(Set("bill", "john"))
    }
    "return empty result for a field excluded by the fields blacklist" excludeES (allEs6x, allEs7xBelowEs714x) in {
      val result = dev9XpackApiManager.getTerms("test2_index", "age.keyword")

      result should have statusCode 200
      result.terms should be(Set.empty)
    }
  }

}

object BaseXpackApiSuite {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    val indexManager = new IndexManager(adminRestClient, esVersion)

    createDocs(indexManager, documentManager)
    storeScriptTemplate(adminRestClient, esVersion)

    indexManager.closeIndex("test3_index_c").force()
  }

  private def createDocs(indexManager: IndexManager, documentManager: DocumentManager): Unit = {
    def createIndexWithMappingAndExampleDocument(indexName: String) = {
      indexManager
        .createIndexWithMapping(
          indexName = indexName,
          propertiesJson = ujson.read("""
                                        |{
                                        |  "hello": {
                                        |    "type": "text",
                                        |    "fields": {
                                        |      "keyword": {
                                        |        "type": "keyword",
                                        |        "ignore_above": 256
                                        |      }
                                        |    }
                                        |  }
                                        |}
                                        |""".stripMargin)
        )
        .force()
      documentManager.createDoc(indexName, 1, ujson.read("""{"hello":"world"}""")).force()
    }

    createIndexWithMappingAndExampleDocument("test1_index_a")
    createIndexWithMappingAndExampleDocument("test1_index_b")

    indexManager
      .createIndexWithMapping(
        indexName = "test2_index",
        propertiesJson = ujson.read("""
                                      |{
                                      |  "age": {
                                      |    "type": "integer",
                                      |    "fields": {
                                      |      "keyword": {
                                      |        "type": "keyword"
                                      |      }
                                      |    }
                                      |  }
                                      |}
                                      |""".stripMargin)
      )
    documentManager.createDoc("test2_index", 1, ujson.read("""{"name":"john", "age":33}""")).force()
    documentManager.createDoc("test2_index", 2, ujson.read("""{"name":"bill", "age":50}""")).force()

    documentManager.createDoc("test3_index_a", 1, ujson.read("""{"timestamp":"2020-01-01", "count": 10}""")).force()
    documentManager.createDoc("test3_index_b", 1, ujson.read("""{"timestamp":"2020-02-01", "count": 100}""")).force()
    documentManager.createDoc("test3_index_c", 1, ujson.read("""{"timestamp":"2020-03-01", "count": 100}""")).force()

    documentManager.createDoc("test4_index_a", 1, ujson.read("""{"timestamp":"2020-01-01", "count": 10}""")).force()
    documentManager.createDoc("test4_index_b", 1, ujson.read("""{"timestamp":"2020-02-01", "count": 100}""")).force()

    documentManager.createDoc("test5_index_a", 1, ujson.read("""{"timestamp":"2020-01-01", "count": 10}""")).force()
    documentManager.createDoc("test5_index_b", 1, ujson.read("""{"timestamp":"2020-02-01", "count": 100}""")).force()

    documentManager.createDoc("test6_index_a", 1, ujson.read("""{"timestamp":"2020-01-01", "count": 10}""")).force()
    documentManager.createDoc("test6_index_b", 1, ujson.read("""{"timestamp":"2020-02-01", "count": 100}""")).force()

    documentManager.createDoc("test7_index", 1, ujson.read("""{"content":{ "app": "a1" }}""")).force()
    documentManager.createDoc("test7_index", 2, ujson.read("""{"content":{ "app": "a2" }}""")).force()
  }

  private def storeScriptTemplate(adminRestClient: RestClient, esVersion: String): Unit = {
    val scriptManager = new ScriptManager(adminRestClient, esVersion)
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

  private object NextRollupJobName {
    private val currentId = Atomic(0)

    def get: String = s"job${currentId.incrementAndGet()}"
  }

}
