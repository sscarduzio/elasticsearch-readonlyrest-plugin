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

import cats.data.NonEmptyList
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.integration.suites.CrossClusterCallsSuite.{localClusterNodeDataInitializer, remoteClusterNodeDataInitializer, remoteClusterSetup}
import tech.beshu.ror.integration.suites.base.support.{BaseEsRemoteClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.integration.utils.ESVersionSupport
import tech.beshu.ror.utils.containers._
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait CrossClusterCallsSuite
  extends WordSpec
    with BaseEsRemoteClusterIntegrationTest
    with SingleClientSupport
    with XpackSupport
    with ESVersionSupport {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/cross_cluster_search/readonlyrest.yml"

  override lazy val targetEs = container.localCluster.nodes.head

  override val remoteClusterContainer: EsRemoteClustersContainer = createRemoteClustersContainer(
    EsClusterSettings(name = "ROR1", nodeDataInitializer = localClusterNodeDataInitializer(), xPackSupport = isUsingXpackSupport),
    NonEmptyList.of(
      EsClusterSettings(name = "ROR2", nodeDataInitializer = remoteClusterNodeDataInitializer(), xPackSupport = isUsingXpackSupport),
    ),
    remoteClusterSetup()
  )

  private lazy val user1SearchManager = new SearchManager(basicAuthClient("dev1", "test"))
  private lazy val user2SearchManager = new SearchManager(basicAuthClient("dev2", "test"))
  private lazy val user3SearchManager = new SearchManager(basicAuthClient("dev3", "test"))

  "A cluster _search for given index" should {
    "return 200 and allow user to its content" when {
      "user has permission to do so" when {
        "he queries local and remote indices"  in {
          val result = user3SearchManager.search("etl:etl*", "metrics*")
          result.responseCode should be(200)
          result.searchHits.map(i => i("_index").str).toSet should be(
            Set("metrics_monitoring_2020-03-26", "metrics_monitoring_2020-03-27", "etl:etl_usage_2020-03-26", "etl:etl_usage_2020-03-27")
          )
        }
        "he queries remote indices only"  in {
          val result = user1SearchManager.search("odd:test1_index")
          result.responseCode should be(200)
          result.searchHits.arr.size should be(2)
        }
      }
    }
    "return empty response" when {
      "user has no permission to do so" when {
        "he queries local and remote indices patterns"  in {
          val result = user2SearchManager.search("etl:etl*", "metrics*")
          result.responseCode should be(200)
          result.searchHits.map(i => i("_index").str).toSet should be (Set.empty)
        }
        "he queries remote indices patterns only"  in {
          val result = user2SearchManager.search("etl:etl*")
          result.responseCode should be(200)
          result.searchHits.map(i => i("_index").str).toSet should be (Set.empty)
        }
      }
    }
    "return 404" when {
      "user has no permission to do so" when {
        "he queries local and remote indices"  in {
          val result = user2SearchManager.search("etl:etl_usage_2020-03-26", "metrics_monitoring_2020-03-26")
          result.responseCode should be(404)
        }
        "he queries remote indices only"  in {
          val result = user2SearchManager.search("odd:test1_index")
          result.responseCode should be(404)
        }
      }
    }
  }

  "A cluster _async_search for given index" should {
    "return 200 and allow user to its content" when {
      "user has permission to do so" when {
        "he queries local and remote indices" excludeES(allEs5x, allEs6x, allEs7xBelowEs77x, rorProxy) in {
          val result = user3SearchManager.asyncSearch("etl:etl*", "metrics*")
          result.responseCode should be(200)
          result.searchHits.map(i => i("_index").str).toSet should be(
            Set("metrics_monitoring_2020-03-26", "metrics_monitoring_2020-03-27", "etl:etl_usage_2020-03-26", "etl:etl_usage_2020-03-27")
          )
        }
        "he queries remote indices only" excludeES(allEs5x, allEs6x, allEs7xBelowEs77x, rorProxy) in {
          val result = user1SearchManager.asyncSearch("odd:test1_index")
          result.responseCode should be(200)
          result.searchHits.arr.size should be(2)
        }
      }
    }
    "return empty response" when {
      "user has no permission to do so" when {
        "he queries local and remote indices patterns" excludeES(allEs5x, allEs6x, allEs7xBelowEs77x, rorProxy) in {
          val result = user2SearchManager.asyncSearch("etl:etl*", "metrics*")
          result.responseCode should be(200)
          result.searchHits.map(i => i("_index").str).toSet should be (Set.empty)
        }
        "he queries remote indices patterns only" excludeES(allEs5x, allEs6x, allEs7xBelowEs77x, rorProxy) in {
          val result = user2SearchManager.asyncSearch("etl:etl*")
          result.responseCode should be(200)
          result.searchHits.map(i => i("_index").str).toSet should be (Set.empty)
        }
      }
    }
    "return 404" when {
      "user has no permission to do so" when {
        "he queries local and remote indices" excludeES(allEs5x, allEs6x, allEs7xBelowEs77x, rorProxy) in {
          val result = user2SearchManager.asyncSearch("etl:etl_usage_2020-03-26", "metrics_monitoring_2020-03-26")
          result.responseCode should be(404)
        }
        "he queries remote indices only" excludeES(allEs5x, allEs6x, allEs7xBelowEs77x, rorProxy) in {
          val result = user2SearchManager.asyncSearch("odd:test1_index")
          result.responseCode should be(404)
        }
      }
    }
  }

  "A cluster _msearch for a given index" should {
    "return 200 and allow user to see its content" when {
      "user has permission to do so" when {
        "he queries local and remote indices"  in {
          val result = user3SearchManager.mSearch(
            """{"index":"metrics*"}""",
            """{"query" : {"match_all" : {}}}""",
            """{"index":"etl:etl*"}""",
            """{"query" : {"match_all" : {}}}"""
          )
          result.responseCode should be(200)
          result.responseJson("responses").arr.size should be(2)
          val firstQueryResponse = result.responseJson("responses")(0)
          firstQueryResponse("hits")("hits").arr.map(_ ("_index").str).toSet should be(
            Set("metrics_monitoring_2020-03-26", "metrics_monitoring_2020-03-27")
          )
          val secondQueryResponse = result.responseJson("responses")(1)
          secondQueryResponse("hits")("hits").arr.map(_ ("_index").str).toSet should be(
            Set("etl:etl_usage_2020-03-26", "etl:etl_usage_2020-03-27")
          )
        }
        "he queries remote indices only"  in {
          val result = user3SearchManager.mSearch(
            """{"index":"etl:etl*"}""",
            """{"query" : {"match_all" : {}}}"""
          )
          result.responseCode should be(200)
          result.responseJson("responses").arr.size should be(1)
          val secondQueryResponse = result.responseJson("responses")(0)
          secondQueryResponse("hits")("hits").arr.map(_ ("_index").str).toSet should be(
            Set("etl:etl_usage_2020-03-26", "etl:etl_usage_2020-03-27")
          )
        }
      }
      "user has permission to do only one request" when {
        "both requests contain index patterns"  in {
          val result = user3SearchManager.mSearch(
            """{"index":"test1*"}""",
            """{"query" : {"match_all" : {}}}""",
            """{"index":"etl:etl*"}""",
            """{"query" : {"match_all" : {}}}"""
          )
          result.responseCode should be(200)
          result.responseJson("responses").arr.size should be(2)
          val firstQueryResponse = result.responseJson("responses")(0)
          firstQueryResponse("hits")("hits").arr.toSet should be(Set.empty)
          val secondQueryResponse = result.responseJson("responses")(1)
          secondQueryResponse("hits")("hits").arr.map(_ ("_index").str).toSet should be(
            Set("etl:etl_usage_2020-03-26", "etl:etl_usage_2020-03-27")
          )
        }
        "both requests contain full name indices"  in {
          val result = user3SearchManager.mSearch(
            """{"index":"test1"}""",
            """{"query" : {"match_all" : {}}}""",
            """{"index":"etl:etl_usage_2020-03-26"}""",
            """{"query" : {"match_all" : {}}}"""
          )
          result.responseCode should be(200)
          result.responseJson("responses").arr.size should be(2)
          val firstQueryResponse = result.responseJson("responses")(0)
          firstQueryResponse("status").num should be (404)
          val secondQueryResponse = result.responseJson("responses")(1)
          secondQueryResponse("status").num should be (200)
          secondQueryResponse("hits")("hits").arr.map(_ ("_index").str).toSet should be(
            Set("etl:etl_usage_2020-03-26")
          )
        }
      }
    }
    "return empty response" when {
      "user has no permission to do so" when {
        "he queries local and remote indices patterns"  in {
          val result = user3SearchManager.mSearch(
            """{"index":"metrics_etl*"}""",
            """{"query" : {"match_all" : {}}}""",
            """{"index":"odd:*"}""",
            """{"query" : {"match_all" : {}}}"""
          )
          result.responseCode should be(200)
          result.responseJson("responses").arr.size should be(2)
          val firstQueryResponse = result.responseJson("responses")(0)
          firstQueryResponse("hits")("hits").arr.size should be (0)
          val secondQueryResponse = result.responseJson("responses")(1)
          secondQueryResponse("hits")("hits").arr.size should be (0)
        }
        "he queries remote indices only"  in {
          val result = user3SearchManager.mSearch(
            """{"index":"odd:*"}""",
            """{"query" : {"match_all" : {}}}"""
          )
          result.responseCode should be(200)
          result.responseJson("responses").arr.size should be(1)
          val firstQueryResponse = result.responseJson("responses")(0)
          firstQueryResponse("hits")("hits").arr.size should be (0)
        }
      }
    }
  }

  "A _field_caps for a given index" should {
    "return 200 and allow user to see its content" when {
      "user has permission to do so" when {
        "he queries local and remote indices"  in {
          val result = user3SearchManager.fieldCaps(
            indices = List("metrics*", "etl:etl*"),
            fields = List("counter1", "usage")
          )
          result.responseCode should be(200)
          result.fields.keys.toSet should be (Set("counter1", "usage"))
        }
        "he queries remote indices only"  in {
          val result = user3SearchManager.fieldCaps(
            indices = List("etl:etl*"),
            fields = List("counter1", "usage")
          )
          result.responseCode should be(200)
          result.fields.keys.toSet should be (Set("usage"))
        }
      }
      "user has permission to do only one request" when {
        "both requests contain index patterns"  in {
          val result = user3SearchManager.fieldCaps(
            indices = List("test1*", "etl:etl*"),
            fields = List("hello", "usage")
          )
          result.responseCode should be(200)
          result.fields.keys.toSet should be (Set("usage"))
        }
        "both requests contain full name indices"  in {
          val result = user3SearchManager.fieldCaps(
            indices = List("test1", "etl:etl_usage_2020-03-26"),
            fields = List("hello", "usage")
          )
          result.responseCode should be(200)
          result.fields.keys.toSet should be (Set("usage"))
        }
      }
    }
    "return empty response" when {
      "user has no permission to do so" when {
        "he queries local and remote indices patterns"  in {
          val result = user3SearchManager.fieldCaps(
            indices = List("metrics_etl*", "odd:*"),
            fields = List("hello", "usage", "counter1")
          )
          result.responseCode should be(200)
          result.fields.keys.toSet should be (Set.empty)
        }
        "he queries remote indices only"  in {
          val result = user3SearchManager.fieldCaps(
            indices = List("odd:*"),
            fields = List("hello", "usage", "counter1")
          )
          result.responseCode should be(200)
          result.fields.keys.toSet should be (Set.empty)
        }
      }
    }
  }
}

object CrossClusterCallsSuite {

  def localClusterNodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    documentManager.createFirstDoc("metrics_monitoring_2020-03-26", ujson.read("""{"counter1":"100"}"""))
    documentManager.createFirstDoc("metrics_monitoring_2020-03-27",  ujson.read("""{"counter1":"50"}"""))
  }

  def remoteClusterNodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    documentManager.createDoc("test1_index", 1, ujson.read("""{"hello":"world"}"""))
    documentManager.createDoc("test1_index", 2, ujson.read("""{"hello":"world"}"""))
    documentManager.createDoc("test2_index", 1, ujson.read("""{"hello":"world"}"""))
    documentManager.createDoc("test2_index", 2, ujson.read("""{"hello":"world"}"""))

    documentManager.createDoc("etl_usage_2020-03-26", 1, ujson.read("""{"usage":"ROR"}"""))
    documentManager.createDoc("etl_usage_2020-03-27", 1, ujson.read("""{"usage":"ROR"}"""))
  }

  def remoteClusterSetup(): SetupRemoteCluster = (remoteClusters: NonEmptyList[EsClusterContainer]) => {
    Map(
      "odd" -> remoteClusters.head,
      "etl" -> remoteClusters.head
    )
  }
}
