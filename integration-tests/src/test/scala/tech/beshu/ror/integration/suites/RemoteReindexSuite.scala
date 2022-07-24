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

import cats.data.NonEmptyList
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.{BaseManyEsClustersIntegrationTest, MultipleClientsSupport}
import tech.beshu.ror.integration.utils.ESVersionSupportForAnyWordSpecLike
import tech.beshu.ror.utils.containers.EsClusterSettings.{ClusterType, EsVersion}
import tech.beshu.ror.utils.containers._
import tech.beshu.ror.utils.containers.images.ReadonlyRestPlugin.Config.Attributes
import tech.beshu.ror.utils.elasticsearch.IndexManager.ReindexSource
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, IndexManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait RemoteReindexSuite
  extends AnyWordSpec
    with BaseManyEsClustersIntegrationTest
    with MultipleClientsSupport
    with BeforeAndAfterEach
    with ESVersionSupportForAnyWordSpecLike
    with Matchers {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/reindex_multi_containers/readonlyrest_dest_es.yml"
  private val sourceEsRorConfigFileName = "/reindex_multi_containers/readonlyrest_source_es.yml"

  private lazy val sourceEsCluster = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR_SOURCE_ES",
      nodeDataInitializer = RemoteReindexSuite.sourceEsDataInitializer(),
      esVersion = EsVersion.SpecificVersion("es60x"),
      clusterType = ClusterType.RorCluster(Attributes.default.copy(
        restSslEnabled = false
      ))
    )(sourceEsRorConfigFileName)
  )

  private lazy val destEsCluster = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR_DEST_ES",
      rorContainerSpecification = ContainerSpecification(
        environmentVariables = Map.empty,
        additionalElasticsearchYamlEntries = Map("reindex.remote.whitelist" -> "\"*:9200\"")
      ),
      clusterType = ClusterType.RorCluster(Attributes.default.copy(
        restSslEnabled = false
      ))
    )(rorConfigFileName)
  )

  private lazy val destEsIndexManager = new IndexManager(clients.last.basicAuthClient("dev1", "test"), esVersionUsed)

  private lazy val sourceEsContainer = sourceEsCluster.nodes.head

  lazy val clusterContainers: NonEmptyList[EsClusterContainer] = NonEmptyList.of(sourceEsCluster, destEsCluster)
  lazy val esTargets: NonEmptyList[EsContainer] = NonEmptyList.of(sourceEsCluster.nodes.head, destEsCluster.nodes.head)

  "A remote reindex request" should {
    "be able to proceed" when {
      "request specifies source and dest index that are allowed on both source and dest ES" in {
        val result = destEsIndexManager.reindex(createReindexSource("test1_index", "dev1"), "test1_index_reindexed")

        result.responseCode should be(200)
      }
    }
    "be blocked by dest ES" when {
      "request specifies source index that is allowed, but dest that isn't allowed" in {
        val result = destEsIndexManager.reindex(createReindexSource("test1_index", "dev1"), "not_allowed_index")

        result.responseCode should be(401)
      }
      "request specifies source index that isn't allowed, but dest that is allowed" in {
        val result = destEsIndexManager.reindex(createReindexSource("not_allowed_index", "dev1"), "test1_index_reindexed")

        result.responseCode should be(401)
      }
      "request specifies both source index and dest index that are not allowed" in {
        val result = destEsIndexManager.reindex(createReindexSource("not_allowed_index", "dev1"), "not_allowed_index_reindexed")

        result.responseCode should be(401)
      }
    }
    "be blocked by source ES" when {
      "request specifies source index that is allowed on dest ES, but is not allowed on source ES" in {
        val result = destEsIndexManager.reindex(createReindexSource("test1_index", "dev3"), "test1_index_reindexed")

        result.responseCode should be(401)
      }
      "request specifies index which is allowed, but is not present in source ES" in {
        val result = destEsIndexManager.reindex(createReindexSource("test2_index", "dev4"), "test2_index_reindexed")

        result.responseCode should be(404)
      }
    }
  }

  private def createReindexSource(sourceIndex: String, username: String): ReindexSource.Remote = {
    ReindexSource.Remote(sourceIndex, s"http://${sourceEsContainer.getAddressInInternalNetwork}", username, "test")
  }
}

object RemoteReindexSuite {
  private def sourceEsDataInitializer(): ElasticsearchNodeDataInitializer = {
    (esVersion: String, adminRestClient: RestClient) => {
      val documentManager = new DocumentManager(adminRestClient, esVersion)
      documentManager.createDoc("test1_index", "Sometype", 1, ujson.read("""{"hello":"world"}"""))
    }
  }
}
