package tech.beshu.ror.integration.suites

import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.integration.suites.base.support.BasicSingleNodeEsClusterSupport
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{ClusterManager, DocumentManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait ClusterApiSuite
  extends WordSpec
    with BasicSingleNodeEsClusterSupport {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/cluster_api/readonlyrest.yml"
  override val nodeDataInitializer = Some(ClusterApiSuite.nodeDataInitializer())

  private val dev1ClusterManager = new ClusterManager(basicAuthClient("dev1", "test"), esVersion = targetEs.esVersion)
  private val dev2ClusterManager = new ClusterManager(basicAuthClient("dev2", "test"), esVersion = targetEs.esVersion)

  "Cluster allocation explain API" should {
    "allow to be used" when {
      "user has access to given index" in {
        val result = dev1ClusterManager.allocationExplain("test1_index")
        result.responseCode should be (200)
      }
    }
    "not allow to be used" when {
      "user doesn't have access to given index" when {
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
  }
}


object ClusterApiSuite {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)

    documentManager.createFirstDoc("test1_index", ujson.read("""{"hello":"world"}"""))
    documentManager.createFirstDoc("test2_index", ujson.read("""{"hello":"world"}"""))
  }
}
