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

import org.scalatest.Matchers._
import cats.data.NonEmptyList
import org.scalatest.WordSpec
import tech.beshu.ror.integration.suites.CrossClusterSearchSuite.{localClusterNodeDataInitializer, remoteClusterNodeDataInitializer, remoteClusterSetup}
import tech.beshu.ror.integration.suites.base.support.{BaseIntegrationTest, SingleClientSupport}
import tech.beshu.ror.integration.utils.ESVersionSupport
import tech.beshu.ror.utils.containers.generic._
import tech.beshu.ror.utils.elasticsearch.{DocumentManagerJ, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait CrossClusterSearchSuite
  extends WordSpec
    with BaseIntegrationTest
    with SingleClientSupport
    with ESVersionSupport {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/cross_cluster_search/readonlyrest.yml"

  override lazy val targetEs = container.localCluster.nodes.head

  override lazy val container = createRemoteClustersContainer(
    EsClusterSettings(name = "ROR1", nodeDataInitializer = localClusterNodeDataInitializer()),
    NonEmptyList.of(
      EsClusterSettings(name = "ROR2", nodeDataInitializer = remoteClusterNodeDataInitializer()),
    ),
    remoteClusterSetup()
  )

  private lazy val user1SearchManager = new SearchManager(client("dev1", "test"))
  private lazy val user2SearchManager = new SearchManager(client("dev2", "test"))

  "A cluster search for given index" should {
    "return 200 and allow user to its content" when {
      "user has permission to do so" excludeES("es51x", "es52x") in {
        val result = user1SearchManager.search("/odd:test1_index/_search")
        result.responseCode should be (200)
        result.searchHits.get.arr.size should be (2)
      }
    }
    "return 401" when {
      "user has no permission to do so" excludeES("es51x", "es52x") in {
        val result = user2SearchManager.search("/odd:test1_index/_search")
        result.responseCode should be (401)
      }
    }
  }

  "A cluster msearch for a given index" should {
    "return 200 and allow user to its content" when {
      "user has permission to do so" excludeES("es51x", "es52x") in {
        val user3SearchManager = new SearchManager(client("dev3", "test"))
        val result = user3SearchManager.mSearch(
          """{"index":"metrics-*"}""",
          """{"query" : {"match_all" : {}}}""",
          """{"index":"etl:etl*"}""",
          """{"query" : {"match_all" : {}}}"""
        )
        result.responseCode should be (200)
        result.responseJson("responses").arr.size should be (2)
        val firstQueryResponse = result.responseJson("responses")(0)
        firstQueryResponse("hits")("hits").arr.map(_("_index").str).toSet should be (
          Set("metrics-monitoring-2020-03-26", "metrics-monitoring-2020-03-27")
        )
        val secondQueryResponse = result.responseJson("responses")(1)
        secondQueryResponse("hits")("hits").arr.map(_("_index").str).toSet should be (
          Set("etl:etl_usage_2020-03-26", "etl:etl_usage_2020-03-27")
        )
      }
    }
  }
}

object CrossClusterSearchSuite {

  def localClusterNodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    val documentManager = new DocumentManagerJ(adminRestClient)
    documentManager.insertDoc("/metrics-monitoring-2020-03-26/test/1",  s"""{"metrics":"counter=1"}""")
    documentManager.insertDoc("/metrics-monitoring-2020-03-27/test/1",  s"""{"metrics":"counter=1"}""")
  }

  def remoteClusterNodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    val documentManager = new DocumentManagerJ(adminRestClient)
    documentManager.insertDoc("/test1_index/test/1", """{"hello":"world"}""")
    documentManager.insertDoc("/test1_index/test/2", """{"hello":"world"}""")
    documentManager.insertDoc("/test2_index/test/1", """{"hello":"world"}""")
    documentManager.insertDoc("/test2_index/test/2", """{"hello":"world"}""")

    documentManager.insertDoc("/etl_usage_2020-03-26/test/2", """{"hello":"ROR"}""")
    documentManager.insertDoc("/etl_usage_2020-03-27/test/2", """{"hello":"ROR"}""")
  }

  def remoteClusterSetup(): SetupRemoteCluster = (remoteClusters: NonEmptyList[EsClusterContainer]) => {
    Map(
      "odd" -> remoteClusters.head,
      "etl" -> remoteClusters.head
    )
  }
}
