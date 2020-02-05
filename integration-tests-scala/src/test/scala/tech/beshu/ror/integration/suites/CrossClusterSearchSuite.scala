package tech.beshu.ror.integration.suites

import cats.data.NonEmptyList
import com.dimafeng.testcontainers.ForAllTestContainer
import org.junit.Assert.assertEquals
import org.scalatest.WordSpec
import tech.beshu.ror.integration.utils.ESVersionSupport
import tech.beshu.ror.utils.containers.generic._
import tech.beshu.ror.utils.elasticsearch.{DocumentManagerJ, SearchManagerJ}
import tech.beshu.ror.utils.httpclient.RestClient

trait CrossClusterSearchSuite
  extends WordSpec
    with ForAllTestContainer
    with ClusterProvider
    with ClientProvider
    with TargetEsContainer
    with ESVersionSupport {
  this: SingleContainerCreator =>

  val rorConfigFileName = "/cross_cluster_search/readonlyrest.yml"
  override val container = createRemoteClustersContainer(
    NonEmptyList.of(
      ClusterSettings(name = "ROR1", rorConfigFileName = rorConfigFileName, nodeDataInitializer = CrossClusterSearchSuite.nodeDataInitializer()),
      ClusterSettings(name = "ROR2", rorConfigFileName = rorConfigFileName, nodeDataInitializer = CrossClusterSearchSuite.nodeDataInitializer()),
    ),
    CrossClusterSearchSuite.remoteClustersInitializer()
  )
  override val targetEsContainer = container.localClusters.head.nodesContainers.head

  private lazy val user1SearchManager = new SearchManagerJ(client("dev1", "test"))
  private lazy val user2SearchManager = new SearchManagerJ(client("dev2", "test"))

  "A cluster search for given index" should {
    "return 200 and allow user to its content" when {
      "user has permission to do so" excludeES("es51x", "es52x") in {
        val result = user1SearchManager.search("/odd:test1_index/_search")
        assertEquals(200, result.getResponseCode)
        assertEquals(2, result.getSearchHits.size)
      }
    }
    "return 401" when {
      "user has no permission to do so" excludeES("es51x", "es52x") in {
        val result = user2SearchManager.search("/odd:test1_index/_search")
        assertEquals(401, result.getResponseCode)
      }
    }
  }
}

object CrossClusterSearchSuite {

  def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    val documentManager = new DocumentManagerJ(adminRestClient)
    documentManager.insertDoc("/test1_index/test/1", "{\"hello\":\"world\"}")
    documentManager.insertDoc("/test1_index/test/2", "{\"hello\":\"ROR\"}")
    documentManager.insertDoc("/test2_index/test/1", "{\"hello\":\"world\"}")
    documentManager.insertDoc("/test2_index/test/2", "{\"hello\":\"ROR\"}")
  }

  def remoteClustersInitializer(): RemoteClustersInitializer =
    (localClusterRepresentatives: NonEmptyList[RorContainer]) => {
      Map("odd" -> localClusterRepresentatives)
    }

}
