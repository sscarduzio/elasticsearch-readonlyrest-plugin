package tech.beshu.ror.integration.suites

import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.integration.suites.base.support.{BaseEsClusterIntegrationTest, SingleClientSupport}
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsClusterContainer, EsClusterSettings, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{CatManager, ClusterManager, DocumentManager, IndexManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers._
import tech.beshu.ror.utils.misc.ScalaUtils.waitForCondition

trait ClusterApiSuite
  extends WordSpec
    with BaseEsClusterIntegrationTest
    with SingleClientSupport {
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

  private lazy val adminCatManager = new CatManager(esTargets.head.basicAuthClient("admin", "container"), esVersion = esTargets.head.esVersion)
  private lazy val dev1ClusterManager = new ClusterManager(esTargets.head.basicAuthClient("dev1", "test"), esVersion = esTargets.head.esVersion)
  private lazy val dev2ClusterManager = new ClusterManager(esTargets.head.basicAuthClient("dev2", "test"), esVersion = esTargets.head.esVersion)
  private lazy val dev3ClusterManager = new ClusterManager(esTargets.head.basicAuthClient("dev3", "test"), esVersion = esTargets.head.esVersion)

  "Cluster allocation explain API" should {
    "allow to be used" when {
      "user has access to given index" in {
        val result = dev1ClusterManager.allocationExplain("test1_index")
        result.responseCode should be(200)
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
        waitForCondition("test2_index index node is STARTED") {
          val test1IndexNodeState = adminCatManager.shards().ofIndex("test1_index").get("state").str
          test1IndexNodeState == "STARTED"
        }

        val nodeOfIndex = adminCatManager.shards().nodeOfIndex("test1_index").get
        val otherNode = adminCatManager.nodes().names.find(_ != nodeOfIndex).get

        val result = dev1ClusterManager.reroute(
          ujson.read(
            s"""{
               |  "move": {
               |    "index": "test1_index",
               |    "shard": 0,
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
          waitForCondition("test2_index index node is STARTED") {
            val test1IndexNodeState = adminCatManager.shards().ofIndex("test2_index").get("state").str
            test1IndexNodeState == "STARTED"
          }

          val nodeOfIndex = adminCatManager.shards().nodeOfIndex("test2_index").get
          val otherNode = adminCatManager.nodes().names.find(_ != nodeOfIndex).get

          val result = dev1ClusterManager.reroute(
            ujson.read(
              s"""{
                 |  "move": {
                 |    "index": "test2_index",
                 |    "shard": 0,
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
}

object ClusterApiSuite {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val indexManager = new IndexManager(adminRestClient)
    val documentManager = new DocumentManager(adminRestClient, esVersion)

    documentManager.createFirstDoc("test1_index", ujson.read("""{"hello":"world"}"""))
    documentManager.createFirstDoc("test2_index", ujson.read("""{"hello":"world"}"""))
    indexManager.putAllSettings(numberOfReplicas = 0)
  }
}
