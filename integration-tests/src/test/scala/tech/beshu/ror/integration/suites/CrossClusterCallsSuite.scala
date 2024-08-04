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
import com.typesafe.scalalogging.StrictLogging
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.{BaseEsRemoteClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyWordSpecLike, PluginTestSupport}
import tech.beshu.ror.utils.containers.SecurityType.{RorWithXpackSecurity, XPackSecurity}
import tech.beshu.ror.utils.containers.*
import tech.beshu.ror.utils.containers.images.domain.Enabled
import tech.beshu.ror.utils.containers.images.{ReadonlyRestWithEnabledXpackSecurityPlugin, XpackSecurityPlugin}
import tech.beshu.ror.utils.elasticsearch.{EnhancedDataStreamManager, DataStreamManager, DocumentManager, IndexManager, IndexTemplateManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.{CustomScalaTestMatchers, Version}

import scala.concurrent.duration.*
import scala.language.postfixOps

class CrossClusterCallsSuite
  extends AnyWordSpec
    with BaseEsRemoteClusterIntegrationTest
    with PluginTestSupport
    with SingleClientSupport
    with ESVersionSupportForAnyWordSpecLike
    with CustomScalaTestMatchers
    with Eventually {

  import tech.beshu.ror.integration.suites.CrossClusterCallsSuite._

  override implicit val rorConfigFileName: String = "/cross_cluster_search/readonlyrest.yml"

  override lazy val targetEs = container.localCluster.nodes.head

  override val remoteClusterContainer: EsRemoteClustersContainer = createRemoteClustersContainer(
    esClusterSettings = EsClusterSettings.create(
      clusterName = "ROR_L1",
      securityType = RorWithXpackSecurity(ReadonlyRestWithEnabledXpackSecurityPlugin.Config.Attributes.default.copy(
        rorConfigFileName = rorConfigFileName,
        internodeSsl = Enabled.Yes(ReadonlyRestWithEnabledXpackSecurityPlugin.Config.InternodeSsl.Xpack)
      )),
      nodeDataInitializer = localClusterNodeDataInitializer(),
    ),
    remoteClustersSettings = NonEmptyList.of(
      xpackClusterSettings(),
      rorClusterSettings("ROR_R1", privateRemoteClusterNodeDataInitializer()),
      rorClusterSettings("ROR_R2", publicRemoteClusterNodeDataInitializer())
    ),
    remoteClusterSetup()
  )

  private def xpackClusterSettings() = EsClusterSettings.create(
    clusterName = "XPACK",
    securityType = XPackSecurity(XpackSecurityPlugin.Config.Attributes.default.copy(
      internodeSslEnabled = true
    )),
    nodeDataInitializer = xpackRemoteClusterNodeDataInitializer()
  )

  private def rorClusterSettings(name: String,
                                 nodeDataInitializer: ElasticsearchNodeDataInitializer) = {
    EsClusterSettings.create(
      clusterName = name,
      securityType = RorWithXpackSecurity(ReadonlyRestWithEnabledXpackSecurityPlugin.Config.Attributes.default.copy(
        rorConfigFileName = rorConfigFileName,
        internodeSsl = Enabled.Yes(ReadonlyRestWithEnabledXpackSecurityPlugin.Config.InternodeSsl.Xpack)
      )),
      nodeDataInitializer = nodeDataInitializer
    )
  }

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(15, Seconds)), interval = scaled(Span(100, Millis)))

  private lazy val user1SearchManager = new SearchManager(basicAuthClient("dev1", "test"), esVersionUsed)
  private lazy val user2SearchManager = new SearchManager(basicAuthClient("dev2", "test"), esVersionUsed)
  private lazy val user3SearchManager = new SearchManager(basicAuthClient("dev3", "test"), esVersionUsed)
  private lazy val user4SearchManager = new SearchManager(basicAuthClient("dev4", "test"), esVersionUsed)
  private lazy val user5SearchManager = new SearchManager(basicAuthClient("dev5", "test"), esVersionUsed)
  private lazy val user1IndexManager = new IndexManager(basicAuthClient("dev1", "test"), esVersionUsed)

  "A cluster _search for given index" should {
    "return 200 and allow user to its content" when {
      "user has permission to do so" when {
        "he queries local and remote indices" in eventually {
          val result = user3SearchManager.search("private1:audit*", "metrics*")
          result should have statusCode 200
          result.searchHits.map(i => i("_index").str).toSet should be(
            Set("metrics_2020-03-26", "metrics_2020-03-27", "private1:audit_2020-03-26", "private1:audit_2020-03-27")
          )
        }
        "he queries remote indices only" in {
          val result = user1SearchManager.search("private2:test1_index")
          result should have statusCode 200
          result.searchHits.arr.size should be(2)
        }
        "he queries remote data streams only by data stream name" excludeES (allEs7xBelowEs77x) in {
          val result = user1SearchManager.search("private2:test1_ds")
          result should have statusCode 200
          val searchResults = result.searchHits.map(_("_source").obj("message").str)
          searchResults.sorted should be(List("message1", "message2", "message3"))
        }
        "he queries remote data streams only by data stream name with wildcard" excludeES (allEs7xBelowEs77x) in {
          val result = user1SearchManager.search("private2:test1_d*")
          result should have statusCode 200
          val searchResults = result.searchHits.map(_("_source").obj("message").str)
          searchResults.sorted should be(List("message1", "message2", "message3"))
        }
        "he queries remote data streams only by data stream backing indices" excludeES (allEs7xBelowEs77x) in {
          val resolveIndexResponse = user1IndexManager.resolve("private2:test1_ds")
          resolveIndexResponse.dataStreams.size should be(1)
          val dataStream = resolveIndexResponse.dataStreams.head
          val searchResults = dataStream.backingIndices.flatMap { backingIndex =>
            val result = user1SearchManager.search(s"private2:$backingIndex")
            result should have statusCode 200
            result.searchHits.map(_("_source").obj("message").str)
          }
          searchResults.sorted should be(List("message1", "message2", "message3"))
        }
        "he queries remote data streams only by data stream backing index with wildcard" excludeES (allEs7xBelowEs77x) in {
          val result = user1SearchManager.search(s"private2:.ds-test1_ds*")
          result should have statusCode 200
          val searchResults = result.searchHits.map(_("_source").obj("message").str)
          searchResults.sorted should be(List("message1", "message2", "message3"))
        }
        "he queries remote xpack cluster indices" in {
          val result = user5SearchManager.search("xpack:xpack*")
          result should have statusCode 200
          result.searchHits.arr.size should be(2)
        }
        "he searches remote xpack cluster using scroll" excludeES (allEs6x) in {
          val result1 = user5SearchManager.searchScroll(
            size = 1,
            scroll = 1 minute,
            "xpack:xpack*"
          )
          result1 should have statusCode 200
          result1.searchHits.arr.size should be(1)

          val result2 = user5SearchManager.searchScroll(result1.scrollId)
          result2 should have statusCode 200
          result2.searchHits.arr.size should be(1)

          val result3 = user5SearchManager.searchScroll(result1.scrollId)
          result3 should have statusCode 404
        }
      }
    }
    "return empty response" when {
      "user has no permission to do so" when {
        "he queries local and remote indices patterns" in {
          val result = user2SearchManager.search("private1:audit*", "metrics*")
          result should have statusCode 200
          result.searchHits.map(i => i("_index").str).toSet should be(Set.empty)
        }
        "he queries remote indices patterns only" in {
          val result = user2SearchManager.search("private1:audit*")
          result should have statusCode 200
          result.searchHits.map(i => i("_index").str).toSet should be(Set.empty)
        }
      }
    }
    "return 404" when {
      "user has no permission to do so" when {
        "he queries local and remote indices" in {
          val result = user2SearchManager.search("private:audit_2020-03-26", "metrics_2020-03-26")
          result should have statusCode 404
        }
        "he queries remote indices only" in {
          val result = user2SearchManager.search("private2:test1_index")
          result should have statusCode 404
        }
      }
    }
    "be forbidden" when {
      "we want to forbid certain names of indices for a given user" when {
        "local indices are used" when {
          "requested index name with wildcard is the same as configured index name with wildcard" in {
            val result = user4SearchManager.search("*-logs-*")
            result should have statusCode 403
          }
          "requested index name with wildcard is more general version of the configured index name with wildcard" in {
            val result = user4SearchManager.search("*-log*")
            result should have statusCode 403
          }
          "requested index name with wildcard is more specialized version of the configured index name with wildcard" in {
            val result = user4SearchManager.search("*-logs-2020*")
            result should have statusCode 403
          }
          "requested index name with wildcard doesn't match the configured index name with wildcard but it does match the resolved index name" in {
            val result = user4SearchManager.search("service1*")
            result should have statusCode 403
          }
        }
        "remote indices are used" when {
          "requested index name with wildcard is the same as configured index name with wildcard" in {
            val result = user4SearchManager.search("p*:*-logs-*")
            result should have statusCode 403
          }
          "requested index name with wildcard is more general version of the configured index name with wildcard" in {
            val result = user4SearchManager.search("p*:*-log*")
            result should have statusCode 403
          }
          "requested index name with wildcard is more specialized version of the configured index name with wildcard" in {
            val result = user4SearchManager.search("p*:*-logs-2020*")
            result should have statusCode 403
          }
          "requested index name with wildcard doesn't match the configured index name with wildcard but it does match the resolved index name" in {
            val result = user4SearchManager.search("p*:service2*")
            result should have statusCode 403
          }
        }
      }
    }
  }

  "A cluster _async_search for given index" should {
    "return 200 and allow user to its content" when {
      "user has permission to do so" when {
        "he queries local and remote indices" excludeES(allEs6x, allEs7xBelowEs77x) in {
          val result = user3SearchManager.asyncSearch("private1:audit*", "metrics*")
          result should have statusCode 200
          result.searchHits.map(i => i("_index").str).toSet should be(
            Set("metrics_2020-03-26", "metrics_2020-03-27", "private1:audit_2020-03-26", "private1:audit_2020-03-27")
          )
        }
        "he queries remote indices only" excludeES(allEs6x, allEs7xBelowEs77x) in {
          val result = user1SearchManager.asyncSearch("private2:test1_index")
          result should have statusCode 200
          result.searchHits.arr.size should be(2)
        }
        "he queries remote data streams by data stream name" excludeES(allEs6x, allEs7xBelowEs77x) in {
          val result = user1SearchManager.asyncSearch("private2:test1_ds")
          result should have statusCode 200
          val searchResults = result.searchHits.map(_("_source").obj("message").str)
          searchResults.sorted should be(List("message1", "message2", "message3"))
        }
        "he queries remote data streams only by data stream name with wildcard" excludeES (allEs7xBelowEs77x) in {
          val result = user1SearchManager.asyncSearch("private2:test1_d*")
          result should have statusCode 200
          val searchResults = result.searchHits.map(_("_source").obj("message").str)
          searchResults.sorted should be(List("message1", "message2", "message3"))
        }
        "he queries remote data streams only by data stream backing indices" excludeES (allEs7xBelowEs77x) in {
          val resolveIndexResponse = user1IndexManager.resolve("private2:test1_ds")
          resolveIndexResponse.dataStreams.size should be(1)
          val dataStream = resolveIndexResponse.dataStreams.head
          val searchResults = dataStream.backingIndices.flatMap { backingIndex =>
            val result = user1SearchManager.asyncSearch(s"private2:$backingIndex")
            result should have statusCode 200
            result.searchHits.map(_("_source").obj("message").str)
          }
          searchResults.sorted should be(List("message1", "message2", "message3"))
        }
        "he queries remote data streams only by data stream backing index with wildcard" excludeES (allEs7xBelowEs77x) in {
          val result = user1SearchManager.asyncSearch(s"private2:.ds-test1_ds*")
          result should have statusCode 200
          val searchResults = result.searchHits.map(_("_source").obj("message").str)
          searchResults.sorted should be(List("message1", "message2", "message3"))
        }
        "he queries remote index which is not forbidden in 'pub' remote cluster" excludeES(allEs6x, allEs7xBelowEs77x) in {
          val result = user4SearchManager.asyncSearch("pu*:*logs*")
          result should have statusCode 200
          result.searchHits.arr.size should be(3)
        }
      }
    }
    "return empty response" when {
      "user has no permission to do so" when {
        "he queries local and remote indices patterns" excludeES(allEs6x, allEs7xBelowEs77x) in {
          val result = user2SearchManager.asyncSearch("private:audit*", "metrics*")
          result should have statusCode 200
          result.searchHits.map(i => i("_index").str).toSet should be(Set.empty)
        }
        "he queries remote indices patterns only" excludeES(allEs6x, allEs7xBelowEs77x) in {
          val result = user2SearchManager.asyncSearch("private:audit*")
          result should have statusCode 200
          result.searchHits.map(i => i("_index").str).toSet should be(Set.empty)
        }
      }
    }
    "return 404" when {
      "user has no permission to do so" when {
        "he queries local and remote indices" excludeES(allEs6x, allEs7xBelowEs77x) in {
          val result = user2SearchManager.asyncSearch("private:audit_2020-03-26", "metrics_2020-03-26")
          result should have statusCode 404
        }
        "he queries remote indices only" excludeES(allEs6x, allEs7xBelowEs77x) in {
          val result = user2SearchManager.asyncSearch("private2:test1_index")
          result should have statusCode 404
        }
      }
    }
    "be forbidden" when {
      "we want to forbid certain names of indices for a given user" when {
        "local indices are used" when {
          "requested index name with wildcard is the same as configured index name with wildcard" excludeES(allEs6x, allEs7xBelowEs77x) in {
            val result = user4SearchManager.asyncSearch("*-logs-*")
            result should have statusCode 403
          }
          "requested index name with wildcard is more general version of the configured index name with wildcard" excludeES(allEs6x, allEs7xBelowEs77x) in {
            val result = user4SearchManager.asyncSearch("*-log*")
            result should have statusCode 403
          }
          "requested index name with wildcard is more specialized version of the configured index name with wildcard" excludeES(allEs6x, allEs7xBelowEs77x) in {
            val result = user4SearchManager.asyncSearch("*-logs-2020*")
            result should have statusCode 403
          }
          "requested index name with wildcard doesn't match the configured index name with wildcard but it does match the resolved index name" excludeES(allEs6x, allEs7xBelowEs77x) in {
            val result = user4SearchManager.asyncSearch("service1*")
            result should have statusCode 403
          }
        }
        "remote indices are used" when {
          "requested index name with wildcard is the same as configured index name with wildcard" excludeES(allEs6x, allEs7xBelowEs77x) in {
            val result = user4SearchManager.asyncSearch("p*:*-logs-*")
            result should have statusCode 403
          }
          "requested index name with wildcard is more general version of the configured index name with wildcard" excludeES(allEs6x, allEs7xBelowEs77x) in {
            val result = user4SearchManager.asyncSearch("p*:*-logs-*")
            result should have statusCode 403
          }
          "requested index name with wildcard is more specialized version of the configured index name with wildcard" excludeES(allEs6x, allEs7xBelowEs77x) in {
            val result = user4SearchManager.asyncSearch("p*:*-logs-2020*")
            result should have statusCode 403
          }
          "requested index name with wildcard doesn't match the configured index name with wildcard but it does match the resolved index name" excludeES(allEs6x, allEs7xBelowEs77x) in {
            val result = user4SearchManager.asyncSearch("p*:service2*")
            result should have statusCode 403
          }
        }
      }
    }
  }

  "A cluster _msearch for a given index" should {
    "return 200 and allow user to see its content" when {
      "user has permission to do so" when {
        "he queries local and remote indices" in {
          val result = user3SearchManager.mSearch(
            """{"index":"metrics*"}""",
            """{"query" : {"match_all" : {}}}""",
            """{"index":"private1:audit*"}""",
            """{"query" : {"match_all" : {}}}"""
          )
          result should have statusCode 200
          result.responseJson("responses").arr.size should be(2)
          val firstQueryResponse = result.responseJson("responses")(0)
          firstQueryResponse("hits")("hits").arr.map(_("_index").str).toSet should be(
            Set("metrics_2020-03-26", "metrics_2020-03-27")
          )
          val secondQueryResponse = result.responseJson("responses")(1)
          secondQueryResponse("hits")("hits").arr.map(_("_index").str).toSet should be(
            Set("private1:audit_2020-03-26", "private1:audit_2020-03-27")
          )
        }
        "he queries remote indices only" in {
          val result = user3SearchManager.mSearch(
            """{"index":"private1:audit*"}""",
            """{"query" : {"match_all" : {}}}"""
          )
          result should have statusCode 200
          result.responseJson("responses").arr.size should be(1)
          val secondQueryResponse = result.responseJson("responses")(0)
          secondQueryResponse("hits")("hits").arr.map(_("_index").str).toSet should be(
            Set("private1:audit_2020-03-26", "private1:audit_2020-03-27")
          )
        }
      }
      "user has permission to do only one request" when {
        "both requests contain index patterns" in {
          val result = user3SearchManager.mSearch(
            """{"index":"test1*"}""",
            """{"query" : {"match_all" : {}}}""",
            """{"index":"private1:audit*"}""",
            """{"query" : {"match_all" : {}}}"""
          )
          result should have statusCode 200
          result.responseJson("responses").arr.size should be(2)
          val firstQueryResponse = result.responseJson("responses")(0)
          firstQueryResponse("hits")("hits").arr.toSet should be(Set.empty)
          val secondQueryResponse = result.responseJson("responses")(1)
          secondQueryResponse("hits")("hits").arr.map(_("_index").str).toSet should be(
            Set("private1:audit_2020-03-26", "private1:audit_2020-03-27")
          )
        }
        "both requests contain full name indices" in {
          val result = user3SearchManager.mSearch(
            """{"index":"test1"}""",
            """{"query" : {"match_all" : {}}}""",
            """{"index":"private1:audit_2020-03-26"}""",
            """{"query" : {"match_all" : {}}}"""
          )
          result should have statusCode 200
          result.responseJson("responses").arr.size should be(2)
          val firstQueryResponse = result.responseJson("responses")(0)
          firstQueryResponse("status").num should be(404)
          val secondQueryResponse = result.responseJson("responses")(1)
          secondQueryResponse("status").num should be(200)
          secondQueryResponse("hits")("hits").arr.map(_("_index").str).toSet should be(
            Set("private1:audit_2020-03-26")
          )
        }
      }
    }
    "return empty response" when {
      "user has no permission to do so" when {
        "he queries local and remote indices patterns" in {
          val result = user3SearchManager.mSearch(
            """{"index":"metrics_audit*"}""",
            """{"query" : {"match_all" : {}}}""",
            """{"index":"private2:*"}""",
            """{"query" : {"match_all" : {}}}"""
          )
          result should have statusCode 200
          result.responseJson("responses").arr.size should be(2)
          val firstQueryResponse = result.responseJson("responses")(0)
          firstQueryResponse("hits")("hits").arr.size should be(0)
          val secondQueryResponse = result.responseJson("responses")(1)
          secondQueryResponse("hits")("hits").arr.size should be(0)
        }
        "he queries remote indices only" in {
          val result = user3SearchManager.mSearch(
            """{"index":"private2:*"}""",
            """{"query" : {"match_all" : {}}}"""
          )
          result should have statusCode 200
          result.responseJson("responses").arr.size should be(1)
          val firstQueryResponse = result.responseJson("responses")(0)
          firstQueryResponse("hits")("hits").arr.size should be(0)
        }
      }
    }
  }

  "A _field_caps for a given index" should {
    "return 200 and allow user to see its content" when {
      "user has permission to do so" when {
        "he queries local and remote indices" in {
          val result = user3SearchManager.fieldCaps(
            indices = List("metrics*", "private1:audit*"),
            fields = List("counter1", "usage")
          )
          result should have statusCode 200
          result.fields.keys.toSet should be(Set("counter1", "usage"))
        }
        "he queries remote indices only" in {
          val result = user3SearchManager.fieldCaps(
            indices = List("private1:audit*"),
            fields = List("counter1", "usage")
          )
          result should have statusCode 200
          result.fields.keys.toSet should be(Set("usage"))
        }
      }
      "user has permission to do only one request" when {
        "both requests contain index patterns" in {
          val result = user3SearchManager.fieldCaps(
            indices = List("test1*", "private1:audit*"),
            fields = List("hello", "usage")
          )
          result should have statusCode 200
          result.fields.keys.toSet should be(Set("usage"))
        }
        "both requests contain full name indices" in {
          val result = user3SearchManager.fieldCaps(
            indices = List("test1", "private1:audit_2020-03-26"),
            fields = List("hello", "usage")
          )
          result should have statusCode 200
          result.fields.keys.toSet should be(Set("usage"))
        }
      }
    }
    "return empty response" when {
      "user has no permission to do so" when {
        "he queries local and remote indices patterns" in {
          val result = user3SearchManager.fieldCaps(
            indices = List("metrics_audit*", "private2:*"),
            fields = List("hello", "usage", "counter1")
          )
          result should have statusCode 200
          result.fields.keys.toSet should be(Set.empty)
        }
        "he queries remote indices only" in {
          val result = user3SearchManager.fieldCaps(
            indices = List("private2:*"),
            fields = List("hello", "usage", "counter1")
          )
          result should have statusCode 200
          result.fields.keys.toSet should be(Set.empty)
        }
      }
    }
  }
}

object CrossClusterCallsSuite extends StrictLogging {

  def localClusterNodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    documentManager.createFirstDoc("metrics_2020-03-26", ujson.read("""{"counter1":"100"}""")).force()
    documentManager.createFirstDoc("metrics_2020-03-27", ujson.read("""{"counter1":"50"}""")).force()

    documentManager.createFirstDoc("service1-logs-2020-03-27", ujson.read("""{"counter1":"50"}""")).force()
    documentManager.createFirstDoc("service1-logs-2020-03-28", ujson.read("""{"counter1":"50"}""")).force()
    documentManager.createFirstDoc("service1-logs-2020-03-29", ujson.read("""{"counter1":"50"}""")).force()
  }

  def privateRemoteClusterNodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    val indexManager = new IndexManager(adminRestClient, esVersion)

    documentManager.createDoc("test1_index", 1, ujson.read("""{"hello":"world"}""")).force()
    documentManager.createDoc("test1_index", 2, ujson.read("""{"hello":"world"}""")).force()
    documentManager.createDoc("test2_index", 1, ujson.read("""{"hello":"world"}""")).force()
    documentManager.createDoc("test2_index", 2, ujson.read("""{"hello":"world"}""")).force()

    documentManager.createDoc("audit_2020-03-26", 1, ujson.read("""{"usage":"ROR"}""")).force()
    documentManager.createDoc("audit_2020-03-27", 1, ujson.read("""{"usage":"ROR"}""")).force()

    documentManager.createFirstDoc("service2-logs-2020-03-27", ujson.read("""{"counter1":"50"}""")).force()
    documentManager.createFirstDoc("service2-logs-2020-03-28", ujson.read("""{"counter1":"50"}""")).force()
    documentManager.createFirstDoc("service2-logs-2020-03-29", ujson.read("""{"counter1":"50"}""")).force()

    indexManager.createAliasOf("service2-logs-*", "service2-logs").force()

    if (Version.greaterOrEqualThan(esVersion, 7, 9, 0)) {
      val templateManager = new IndexTemplateManager(adminRestClient, esVersion)
      val dataStreamManager = new DataStreamManager(adminRestClient, esVersion)
      val enhancedDataStreamManager = new EnhancedDataStreamManager(dataStreamManager, documentManager, indexManager, templateManager)
      enhancedDataStreamManager.createDataStream("test1_ds")
      enhancedDataStreamManager.createDocsInDataStream(
        name = "test1_ds",
        messages = NonEmptyList.of("message1", "message2", "message3"),
        rolloverAfterEachDoc = true
      )
    }
  }

  def publicRemoteClusterNodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    documentManager.createFirstDoc("service3-logs-2020-03-27", ujson.read("""{"counter1":"50"}""")).force()
    documentManager.createFirstDoc("service3-logs-2020-03-28", ujson.read("""{"counter1":"50"}""")).force()
    documentManager.createFirstDoc("service3-logs-2020-03-29", ujson.read("""{"counter1":"50"}""")).force()

    val indexManager = new IndexManager(adminRestClient, esVersion)
    indexManager.createAliasOf("service3-logs-*", "service3-logs").force()
  }

  def xpackRemoteClusterNodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    documentManager.createDoc("xpack_cluster_index", 1, ujson.read("""{"counter1":"50"}""")).force()
    documentManager.createDoc("xpack_cluster_index", 2, ujson.read("""{"counter1":"50"}""")).force()
  }

  def remoteClusterSetup(): SetupRemoteCluster = (remoteClusters: NonEmptyList[EsClusterContainer]) => {
    List(
      ("private1", findRemoteClusterByName(name = "ROR_R1", remoteClusters)),
      ("private2", findRemoteClusterByName(name = "ROR_R1", remoteClusters)),
      ("public", findRemoteClusterByName(name = "ROR_R2", remoteClusters)),
      ("xpack", findRemoteClusterByName(name = "XPACK", remoteClusters))
    ) collect { case (name, Some(foundCluster)) => (name, foundCluster) } toMap
  }

  private def findRemoteClusterByName(name: String, remoteClusters: NonEmptyList[EsClusterContainer]) = {
    remoteClusters
      .find(_.nodes.exists(_.esConfig.clusterName == name))
      .orElse {
        logger.warn(s"Cannot find remote cluster with name $name. Skipping")
        None
      }
  }
}
