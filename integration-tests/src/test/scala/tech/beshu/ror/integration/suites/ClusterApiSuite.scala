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

import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.integration.utils.ESVersionSupportForAnyWordSpecLike
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsClusterContainer, EsClusterSettings, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{CatManager, ClusterManager, DocumentManager, IndexManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.ScalaUtils.waitForCondition

trait ClusterApiSuite
  extends AnyWordSpec
    with BaseEsClusterIntegrationTest
    with SingleClientSupport 
    with ESVersionSupportForAnyWordSpecLike {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/cluster_api/readonlyrest.yml"
  override lazy val targetEs = container.nodes.head

  override lazy val clusterContainer: EsClusterContainer = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      numberOfInstances = 2,
      nodeDataInitializer = ClusterApiSuite.nodeDataInitializer()
    )
  )

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
        result.responseCode should be(200)
      }
      "no index is passed and block without no `indices` rule was matched" in {
        val result = dev4ClusterManager.allocationExplain()
        result.responseCode should not be(403)
      }
    }
    "not allow to be used (pretend that index doesn't exist)" when {
      "user doesn't have an access to given index" when {
        "the index doesn't exist" in {
          val result = dev2ClusterManager.allocationExplain("test3_index")
          result.responseCode should be(404)
        }
        "the index does exist" in {
          val result = dev2ClusterManager.allocationExplain("test1_index")
          result.responseCode should be(404)
        }
      }
    }
    "not be allowed" when {
      "no index is passed" in {
        val result = dev1ClusterManager.allocationExplain()
        result.responseCode should be(403)
      }
    }
  }

  "Cluster health API" should {
    "allow to be used" when {
      "no index is specified" in {
        val result = dev1ClusterManager.health()
        result.responseCode should be(200)
        result.responseJson("status").str should not be "red"
      }
      "index is specified and user has access to it" in {
        val result = dev1ClusterManager.health("test1_index")
        result.responseCode should be(200)
        result.responseJson("status").str should not be "red"
      }
    }
    "not allow to be used (pretend that index doesn't exist)" when {
      "user doesn't have an access to specified existing index" in {
        val result = dev2ClusterManager.health("test1_index")
        result.responseCode should be(408)
        result.responseJson("status").str should be("red")
      }
      "user doesn't have an access to specified index which doesn't exist" in {
        val result = dev2ClusterManager.health("test3_index")
        result.responseCode should be(408)
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
        result.responseCode should be(200)
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
          result.responseCode should be(403)
        }
        "the index doesn't exist" in {
          val result = dev1ClusterManager.reroute(
            ujson.read(
              s"""{
                 |  "move": {
                 |    "index": "test1_index_nonexistent",
                 |    "shard": 0,
                 |    "from_node": "${container.nodes(0).name}",
                 |    "to_node": "${container.nodes(1).name}"
                 |  }
                 |}""".stripMargin)
          )
          result.responseCode should be(403)
        }
      }
    }
  }

  "Cluster state API" should {
    "return info about indices" when {
      "user has access to all requested indices" in {
        val result = dev1ClusterManager.state("test1_index")
        result.responseCode should be(200)
        result.responseJson("routing_table")("indices").obj.keys.toSet should be(Set("test1_index"))
      }
      "user has access only to part of requested indices" in {
        val result = dev1ClusterManager.state("test1_index", "test2_index", "test3_index")
        result.responseCode should be(200)
        result.responseJson("routing_table")("indices").obj.keys.toSet should be(Set("test1_index"))
        result.responseJson should not(containKeyOrValue("test2_index"))
        result.responseJson should not(containKeyOrValue("test3_index"))
      }
      "user don't have access to all indices" in {
        val result = dev1ClusterManager.state()
        result.responseCode should be(200)
        result.responseJson("routing_table")("indices").obj.keys.toSet should be(Set("test1_index"))
      }
    }
    "return no info about indices" when {
      "user has access to only not existing ones" in {
        val result = dev3ClusterManager.state("test3_index")
        result.responseCode should be(200)
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
      response.responseCode should be(200)

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
