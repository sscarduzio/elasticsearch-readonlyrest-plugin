package tech.beshu.ror.integration.suites

import org.junit.Assert.assertEquals
import org.scalatest.{Matchers, WordSpec}
import tech.beshu.ror.integration.suites.base.support.{BaseIntegrationTest, SingleClientSupport}
import tech.beshu.ror.utils.containers.generic.{ElasticsearchNodeDataInitializer, EsClusterSettings, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{DeleteByQueryManagerJ, ElasticsearchTweetsInitializer}
import tech.beshu.ror.utils.httpclient.RestClient

trait DeleteByQuerySuite
  extends WordSpec
    with BaseIntegrationTest
    with SingleClientSupport
    with Matchers {
  this: EsContainerCreator =>

  private val matchAllQuery = "{\"query\" : {\"match_all\" : {}}}\n"

  override implicit val rorConfigFileName = "/delete_by_query/readonlyrest.yml"

  override lazy val targetEs = container.nodesContainers.head

  override lazy val container = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      nodeDataInitializer = DeleteByQuerySuite.nodeDataInitializer()
    )
  )
  private lazy val blueTeamDeleteByQueryManager = new DeleteByQueryManagerJ(basicAuthClient("blue", "dev"))
  private lazy val redTeamDeleteByQueryManager = new DeleteByQueryManagerJ(basicAuthClient("red", "dev"))

  "Delete by query" should {
    "be allowed" when {
      "is executed by blue client" in {
        val response = blueTeamDeleteByQueryManager.delete("twitter", matchAllQuery)
        assertEquals(200, response.getResponseCode)
      }
    }
    "not be alllowed" when {
      "is executed by red client" in {
        val response = redTeamDeleteByQueryManager.delete("facebook", matchAllQuery)
        assertEquals(401, response.getResponseCode)
      }
    }
  }
}

object DeleteByQuerySuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    new ElasticsearchTweetsInitializer().initialize(adminRestClient)
  }
}