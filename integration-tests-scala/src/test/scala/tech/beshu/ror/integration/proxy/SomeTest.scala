package tech.beshu.ror.integration.proxy

import cats.data.NonEmptyList
import com.dimafeng.testcontainers.{Container, ForAllTestContainer}
import org.scalatest.{Matchers, WordSpec}
import tech.beshu.ror.utils.containers.{EsWithoutRorPluginContainer, NoOpElasticsearchNodeDataInitializer}
import tech.beshu.ror.utils.elasticsearch.ClusterStateManager


class SomeTest
  extends WordSpec
    with BaseProxyTest
    with Matchers
    with ForAllTestContainer {

  private val config = EsWithoutRorPluginContainer.Config("NODE1", NonEmptyList.one("NODE1"), "7.5.1", false)
  private lazy val esContainer = EsWithoutRorPluginContainer.create(config, NoOpElasticsearchNodeDataInitializer)
  private lazy val client = esContainer.adminClient

  private lazy val adminClusterStateManager = new ClusterStateManager(client)

  override val container: Container = esContainer

    "Ror proxy" should {
      "start" in {
        val response = adminClusterStateManager.healthCheck()
        response.responseCode should be (200)
      }
  }

}
