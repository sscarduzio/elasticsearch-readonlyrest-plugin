package tech.beshu.ror.integration.suites

import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{ClusterManager, DocumentManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers._

trait ClusterApiSuite
  extends WordSpec
    with BaseSingleNodeEsClusterTest {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/cluster_api/readonlyrest.yml"
  override val nodeDataInitializer = Some(ClusterApiSuite.nodeDataInitializer())

  private val dev1ClusterManager = new ClusterManager(basicAuthClient("dev1", "test"), esVersion = targetEs.esVersion)
  private val dev2ClusterManager = new ClusterManager(basicAuthClient("dev2", "test"), esVersion = targetEs.esVersion)
  private val dev3ClusterManager = new ClusterManager(basicAuthClient("dev3", "test"), esVersion = targetEs.esVersion)

  "Cluster allocation explain API" should {
    "allow to be used" when {
      "user has access to given index" in {
        val result = dev1ClusterManager.allocationExplain("test1_index")
        result.responseCode should be (200)
      }
    }
    "not allow to be used (pretend that index doesn't exist)" when {
      "user doesn't have an access to given index" when {
        "the index doesn't exist" in {
          val result = dev2ClusterManager.allocationExplain("test3_index")
          result.responseCode should be (404)
        }
        "the index does exist" in {
          val result = dev2ClusterManager.allocationExplain("test1_index")
          result.responseCode should be (404)
        }
      }
    }
    "not be allowed" when {
      "no index is passed" in {
        val result = dev1ClusterManager.allocationExplain()
        result.responseCode should be (403)
      }
    }
  }

  "Cluster health API" should {
    "allow to be used" when {
      "no index is specified" in {
        val result = dev1ClusterManager.health()
        result.responseCode should be (200)
        result.responseJson("status").str should not be "red"
      }
      "index is specified and user has access to it" in {
        val result = dev1ClusterManager.health("test1_index")
        result.responseCode should be (200)
        result.responseJson("status").str should not be "red"
      }
    }
    "not allow to be used (pretend that index doesn't exist)" when {
      "user doesn't have an access to specified existing index" in {
        val result = dev2ClusterManager.health("test1_index")
        result.responseCode should be (408)
        result.responseJson("status").str should be ("red")
      }
      "user doesn't have an access to specified index which doesn't exist" in {
        val result = dev2ClusterManager.health("test3_index")
        result.responseCode should be (408)
        result.responseJson("status").str should be ("red")
      }
    }
  }

  // todo:
  "Cluster reroute API" should {
    "allow to be used" when {
      "user has access to given index" in {

      }
    }
    "not allow to be used (pretend that index doesn't exist)" when {
      "user doesn't have an access to given index" when {
        "the index doesn't exist" in {

        }
        "the index does exist" in {

        }
      }
    }
  }

  "Cluster state API" should {
    "return info about indices" when {
      "user has access to all requested indices" in {
        val result = dev1ClusterManager.state("test1_index")
        result.responseCode should be (200)
        result.responseJson("routing_table")("indices").obj.keys.toSet should be (Set("test1_index"))
      }
      "user has access only to part of requested indices" in {
        val result = dev1ClusterManager.state("test1_index", "test2_index", "test3_index")
        result.responseCode should be (200)
        result.responseJson("routing_table")("indices").obj.keys.toSet should be (Set("test1_index"))
        result.responseJson should not (containKeyOrValue("test2_index"))
        result.responseJson should not (containKeyOrValue("test3_index"))
      }
      "user don't have access to all indices" in {
        val result = dev1ClusterManager.state()
        result.responseCode should be (200)
        result.responseJson("routing_table")("indices").obj.keys.toSet should be (Set("test1_index"))
      }
    }
    "return no info about indices" when {
      "user has access to only not existing ones" in {
        val result = dev3ClusterManager.state("test3_index")
        result.responseCode should be (200)
        result.responseJson should not (containKeyOrValue("test3_index"))
      }
    }
  }
}


object ClusterApiSuite {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)

    documentManager.createFirstDoc("test1_index", ujson.read("""{"hello":"world"}"""))
    documentManager.createFirstDoc("test2_index", ujson.read("""{"hello":"world"}"""))
  }
}
