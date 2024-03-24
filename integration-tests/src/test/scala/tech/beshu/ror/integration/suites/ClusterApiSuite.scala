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

import eu.timepit.refined.auto._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyWordSpecLike, PluginTestSupport}
import tech.beshu.ror.utils.containers.SecurityType.RorWithXpackSecurity
import tech.beshu.ror.utils.containers.images.ReadonlyRestWithEnabledXpackSecurityPlugin
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsClusterContainer, EsClusterSettings, SecurityType}
import tech.beshu.ror.utils.elasticsearch.{CatManager, ClusterManager, DocumentManager, IndexManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers
import tech.beshu.ror.utils.misc.ScalaUtils.waitForCondition

class ClusterApiSuite
  extends AnyWordSpec
    with BaseEsClusterIntegrationTest
    with PluginTestSupport
    with SingleClientSupport
    with ESVersionSupportForAnyWordSpecLike
    with CustomScalaTestMatchers {

  override implicit val rorConfigFileName: String = "/cluster_api/readonlyrest.yml"
  override lazy val targetEs = container.nodes.head

  override lazy val clusterContainer: EsClusterContainer = {
    def esClusterSettingsCreator(securityType: SecurityType) = EsClusterSettings.create(
      clusterName = "ROR1",
      numberOfInstances = 2,
      securityType = securityType,
      nodeDataInitializer = ClusterApiSuite.nodeDataInitializer()
    )

    createLocalClusterContainer(
      esClusterSettingsCreator(
        RorWithXpackSecurity(ReadonlyRestWithEnabledXpackSecurityPlugin.Config.Attributes.default.copy(
          rorConfigFileName = rorConfigFileName
        ))
      )
    )
  }

  private lazy val adminCatManager = new CatManager(basicAuthClient("admin", "container"), esVersion = esVersionUsed)
  private lazy val adminClusterManager = new ClusterManager(basicAuthClient("admin", "container"), esVersion = esVersionUsed)
  private lazy val dev1ClusterManager = new ClusterManager(basicAuthClient("dev1", "test"), esVersion = esVersionUsed)
  private lazy val dev2ClusterManager = new ClusterManager(basicAuthClient("dev2", "test"), esVersion = esVersionUsed)
  private lazy val dev3ClusterManager = new ClusterManager(basicAuthClient("dev3", "test"), esVersion = esVersionUsed)
  private lazy val dev4ClusterManager = new ClusterManager(basicAuthClient("dev4", "test"), esVersion = esVersionUsed)

  "Cluster allocation explain API" should {
    "allow to be used" when {
      "user has access to given index" in {
        val result = dev1ClusterManager.allocationExplain("test1_index")
        result should have statusCode 200
      }
      "no index is passed and block without no `indices` rule was matched" in {
        val result = dev4ClusterManager.allocationExplain()
        result.responseCode should not be (403)
      }
    }
    "not allow to be used (pretend that index doesn't exist)" when {
      "user doesn't have an access to given index" when {
        "the index doesn't exist" in {
          val result = dev2ClusterManager.allocationExplain("test3_index")
          result should have statusCode 404
        }
        "the index does exist" in {
          val result = dev2ClusterManager.allocationExplain("test1_index")
          result should have statusCode 404
        }
      }
    }
    "not be allowed" when {
      "no index is passed" in {
        val result = dev1ClusterManager.allocationExplain()
        result should have statusCode 403
      }
    }
  }

  "Cluster health API" should {
    "allow to be used" when {
      "no index is specified" in {
        val result = dev1ClusterManager.health()
        result should have statusCode 200
        result.responseJson("status").str should not be "red"
      }
      "index is specified and user has access to it" in {
        val result = dev1ClusterManager.health("test1_index")
        result should have statusCode 200
        result.responseJson("status").str should not be "red"
      }
    }
    "not allow to be used (pretend that index doesn't exist)" when {
      "user doesn't have an access to specified existing index" in {
        val result = dev2ClusterManager.health("test1_index")
        result should have statusCode 408
        result.responseJson("status").str should be("red")
      }
      "user doesn't have an access to specified index which doesn't exist" in {
        val result = dev2ClusterManager.health("test3_index")
        result should have statusCode 408
        result.responseJson("status").str should be("red")
      }
    }
  }

  "Cluster reroute API" should {
    "allow to be used" when {
      "command with index is used and the user has access to given index" in {
        val indexName = "test1_index"

        waitForCondition(s"$indexName index node is STARTED") {
          val test1IndexNodeState = adminCatManager.shards().ofIndex(indexName).get("state").str
          test1IndexNodeState == "STARTED"
        }

        val shardsResponse = adminCatManager.shards()
        val nodeOfIndex = shardsResponse.nodeOfIndex(indexName).get
        val shardOfIndex = shardsResponse.shardOfIndex(indexName).get
        val otherNode = adminCatManager.nodes().names.find(_ != nodeOfIndex).get

        val result = dev1ClusterManager.reroute(
          ujson.read(
            s"""{
               |  "move": {
               |    "index": "$indexName",
               |    "shard": $shardOfIndex,
               |    "from_node": "$nodeOfIndex",
               |    "to_node": "$otherNode"
               |  }
               |}""".stripMargin)
        )
        result should have statusCode 200
      }
    }
    "not allow to be used (pretend that index doesn't exist)" when {
      "user doesn't have an access to command index" when {
        "the index does exist" in {
          val indexName = "test2_index"
          waitForCondition(s"$indexName index node is STARTED") {
            val test1IndexNodeState = adminCatManager.shards().ofIndex(indexName).get("state").str
            test1IndexNodeState == "STARTED"
          }

          val shardsResponse = adminCatManager.shards()
          val nodeOfIndex = shardsResponse.nodeOfIndex(indexName).get
          val shardOfIndex = shardsResponse.shardOfIndex(indexName).get
          val otherNode = adminCatManager.nodes().names.find(_ != nodeOfIndex).get

          val result = dev1ClusterManager.reroute(
            ujson.read(
              s"""{
                 |  "move": {
                 |    "index": "$indexName",
                 |    "shard": $shardOfIndex,
                 |    "from_node": "$nodeOfIndex",
                 |    "to_node": "$otherNode"
                 |  }
                 |}""".stripMargin)
          )
          result should have statusCode 403
        }
        "the index doesn't exist" in {
          val result = dev1ClusterManager.reroute(
            ujson.read(
              s"""{
                 |  "move": {
                 |    "index": "test1_index_nonexistent",
                 |    "shard": 0,
                 |    "from_node": "${container.nodes(0).esConfig.nodeName}",
                 |    "to_node": "${container.nodes(1).esConfig.nodeName}"
                 |  }
                 |}""".stripMargin)
          )
          result should have statusCode 403
        }
      }
    }
  }

  "Cluster state API" should {
    "return info about indices" when {
      "user has access to all requested indices" in {
        val result = dev1ClusterManager.state("test1_index")
        result should have statusCode 200
        result.responseJson("routing_table")("indices").obj.keys.toSet should be(Set("test1_index"))
      }
      "user has access only to part of requested indices" in {
        val result = dev1ClusterManager.state("test1_index", "test2_index", "test3_index")
        result should have statusCode 200
        result.responseJson("routing_table")("indices").obj.keys.toSet should be(Set("test1_index"))
        result.responseJson should not(containKeyOrValue("test2_index"))
        result.responseJson should not(containKeyOrValue("test3_index"))
      }
      "user don't have access to all indices" in {
        val result = dev1ClusterManager.state()
        result should have statusCode 200
        result.responseJson("routing_table")("indices").obj.keys.toSet should be(Set("test1_index"))
      }
    }
    "return no info about indices" when {
      "user has access to only not existing ones" in {
        val result = dev3ClusterManager.state("test3_index")
        result should have statusCode 200
        result.responseJson should not(containKeyOrValue("test3_index"))
      }
    }
  }

  "Cluster settings API" should {
    "allow user to modify and get current settings" in {
      val settingsBeforePut = adminClusterManager.getSettings

      val response = adminClusterManager.putSettings(
        ujson.read(
          s"""{
             |  "persistent": {
             |    "cluster.routing.allocation.cluster_concurrent_rebalance": "30"
             |  }
             |}""".stripMargin
        )
      )
      response should have statusCode 200

      val settingsAfterPut = adminClusterManager.getSettings
      settingsAfterPut should not be settingsBeforePut
      settingsAfterPut.responseJson("persistent")("cluster")("routing")("allocation")("cluster_concurrent_rebalance").str should be("30")
    }
  }
}

object ClusterApiSuite {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val indexManager = new IndexManager(adminRestClient, esVersion)
    val documentManager = new DocumentManager(adminRestClient, esVersion)

    documentManager.createFirstDoc("test1_index", ujson.read("""{"hello":"world"}"""))
    documentManager.createFirstDoc("test2_index", ujson.read("""{"hello":"world"}"""))
    indexManager.putAllSettings(numberOfReplicas = 0)
  }
}
