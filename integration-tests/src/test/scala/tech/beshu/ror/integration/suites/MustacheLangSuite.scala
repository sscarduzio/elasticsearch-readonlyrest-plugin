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
import tech.beshu.ror.integration.suites.base.support.{BaseIntegrationTest, SingleClientSupport}
import tech.beshu.ror.integration.utils.ESVersionSupport
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsClusterSettings, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{DocumentManagerJ, ScriptManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait MustacheLangSuite
  extends WordSpec
    with BaseIntegrationTest
    with SingleClientSupport
    with ESVersionSupport
    with Matchers {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/plugin_indices/readonlyrest.yml"

  override lazy val targetEs = container.nodes.head

  override lazy val container = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      nodeDataInitializer = MustacheLangSuite.nodeDataInitializer(),
      xPackSupport = true
    )
  )

  "Search can be done" when {
    "user uses local auth rule" when {
      "mustache template can be used" excludeES("es51x", "es52x", "es53x") in {
        val searchManager = new SearchManager(basicAuthClient("dev1", "test"))
        val query =
          s"""
             |{
             |    "id": "template1",
             |    "params": {
             |        "query_string": "world"
             |    }
             |}
          """.stripMargin
        val result = searchManager.search("/test1_index/_search/template", query)

        result.responseCode shouldEqual 200
        result.searchHits(0)("_source") should be(ujson.read("""{"hello":"world"}"""))
      }
    }
  }
  "Template rendering can be done" when {
    "user uses local auth rule" excludeES("es51x", "es52x", "es53x") in {
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

object MustacheLangSuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    val documentManager = new DocumentManagerJ(adminRestClient)
    documentManager.insertDocAndWaitForRefresh("/test1_index/test/1", "{\"hello\":\"world\"}")

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
    scriptManager.store(s"/_scripts/template1", script)
  }
}