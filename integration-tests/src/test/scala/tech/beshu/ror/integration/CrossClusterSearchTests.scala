package tech.beshu.ror.integration

import cats.implicits._
import com.dimafeng.testcontainers.ForAllTestContainer
import org.apache.http.client.methods.HttpPut
import org.apache.http.entity.StringEntity
import org.junit.Assert.assertEquals
import org.scalatest.WordSpec
import tech.beshu.ror.integration.utils.JavaScalaUtils.bracket
import tech.beshu.ror.integration.utils.ScalaUtils._
import tech.beshu.ror.integration.utils.containers.{ElasticsearchNodeDataInitializer, ReadonlyRestEsClusterContainer, ReadonlyRestEsClusterInitializer}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient

import scala.util.Try

class CrossClusterSearchTests extends WordSpec with ForAllTestContainer {

  override val container: ReadonlyRestEsClusterContainer = ReadonlyRestEsClusterContainer.create(
    rorConfigFileName = "/cross_cluster_search/readonlyrest.yml",
    nodeDataInitializer = CrossClusterSearchTests.nodeDataInitializer(),
    clusterInitializer = CrossClusterSearchTests.remoteClustersInitializer()
  )

  private lazy val user1SearchManager = new SearchManager(container.nodesContainers.head.client("dev1", "test"))
  private lazy val user2SearchManager = new SearchManager(container.nodesContainers.head.client("dev2", "test"))

  "A cluster search for given index" should {
    "return 200 and allow user to its content" when {
      "user has permission to do so" in {
        val result = user1SearchManager.search("/odd:test1_index/_search")
        assertEquals(200, result.getResponseCode)
        assertEquals(2, result.getResults.size)

      }
    }
    "return 401" when {
      "user has no permission to do so" in {
        val result = user2SearchManager.search("/odd:test1_index/_search")
        assertEquals(401, result.getResponseCode)
      }
    }
  }
}

object CrossClusterSearchTests {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (documentManager: DocumentManager) => {
    documentManager.insertDoc("/test1_index/test/1", "{\"hello\":\"world\"}")
    documentManager.insertDoc("/test1_index/test/2", "{\"hello\":\"ROR\"}")
    documentManager.insertDoc("/test2_index/test/1", "{\"hello\":\"world\"}")
    documentManager.insertDoc("/test2_index/test/2", "{\"hello\":\"ROR\"}")
  }

  private def remoteClustersInitializer(): ReadonlyRestEsClusterInitializer =
    (adminClient: RestClient, container: ReadonlyRestEsClusterContainer) => {
      def createRemoteClusterSettingsRequest() = {
        val (evenSeeds, oddSeeds) = container.nodesContainers.toList.map(c => s"${c.name}:9300").partitionByIndexMod2
        val request = new HttpPut(adminClient.from("_cluster/settings"))
        request.setHeader("Content-Type", "application/json")
        request.setEntity(new StringEntity(
          s"""
            |{
            |  "persistent": {
            |    "search.remote": {
            |      "even": {
            |        "seeds": [${evenSeeds.map(s => s""""$s"""").mkString(",")}]
            |      },
            |      "odd": {
            |        "seeds": [${oddSeeds.map(s => s""""$s"""").mkString(",")}]
            |      }
            |    }
            |  }
            |}
          """.stripMargin))
        request
      }
      bracket(Try(adminClient.execute(createRemoteClusterSettingsRequest()))) { response =>
        response.getStatusLine.getStatusCode match {
          case 200 =>
          case _ =>
            throw new IllegalStateException("Cannot initialize remote cluster settings")
        }
      }
    }
}
