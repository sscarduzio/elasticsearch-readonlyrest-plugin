package tech.beshu.ror.integration.suites

import org.scalatest.WordSpec
import org.scalatest.Matchers._
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.ESVersionSupport
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, IndexManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait IndexAliasesManagementSuite
  extends WordSpec
    with BaseSingleNodeEsClusterTest
    with ESVersionSupport {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/aliases/readonlyrest.yml"

  override def nodeDataInitializer = Some(IndexAliasesManagementSuite.nodeDataInitializer())

  private lazy val adminIndexManager = new IndexManager(basicAuthClient("admin", "container"))
  private lazy val dev1IndexManager = new IndexManager(basicAuthClient("dev1", "test"))

  "Add index alias API" should {
    "be allowed to use" when {
      "there is no indices rule in block" in {
        val result = adminIndexManager.createAliasOf("index", "admin-alias")
        result.responseCode should be (200)
      }
      "user has access to both: index pattern and alias name" when {
        "an index of the pattern exists" in {
          val result = dev1IndexManager.createAliasOf("dev1-000*", "dev1")
          result.responseCode should be(200)
        }
        "no index of the pattern exists" in {
          val result = dev1IndexManager.createAliasOf("dev1-0000*", "dev1")
          result.responseCode should be(404)
        }
      }
    }
    "not be allowed to use" when {
      "user has no access to given at least one index pattern" in {
        val result = dev1IndexManager.createAliasOf("dev1-000*", "dev2")
        result.responseCode should be (403)
      }
      "user has no access to alias name" in {
        val result = dev1IndexManager.createAliasOf("dev2-000*", "dev1")
        result.responseCode should be (403)
      }
    }
  }

}

object IndexAliasesManagementSuite {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)

    documentManager.createFirstDoc("index", ujson.read("""{"hello":"world"}""")).force()
    documentManager.createFirstDoc("dev1-0001",  ujson.read("""{"hello":"world"}""")).force()
    documentManager.createFirstDoc("dev2-0001", ujson.read("""{"hello":"world"}""")).force()
  }
}

