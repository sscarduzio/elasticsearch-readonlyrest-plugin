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
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.{BaseManyEsClustersIntegrationTest, MultipleClientsSupport}
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyWordSpecLike, PluginTestSupport}
import tech.beshu.ror.utils.containers._
import tech.beshu.ror.utils.containers.images.domain.Enabled
import tech.beshu.ror.utils.containers.images.{ReadonlyRestPlugin, ReadonlyRestWithEnabledXpackSecurityPlugin}
import tech.beshu.ror.utils.elasticsearch.IndexManager.ReindexSource
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, IndexManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers

class RemoteReindexSuite
  extends AnyWordSpec
    with BaseManyEsClustersIntegrationTest
    with PluginTestSupport
    with MultipleClientsSupport
    with BeforeAndAfterEach
    with ESVersionSupportForAnyWordSpecLike
    with CustomScalaTestMatchers {

  override implicit val rorConfigFileName: String = "/reindex_multi_containers/readonlyrest_dest_es.yml"
  private val sourceEsRorConfigFileName = "/reindex_multi_containers/readonlyrest_source_es.yml"

  private lazy val sourceEsCluster = createLocalClusterContainer(
    EsClusterSettings.create(
      clusterName = "ROR_SOURCE_ES",
      nodeDataInitializer = RemoteReindexSuite.sourceEsDataInitializer(),
      esVersion = EsVersion.SpecificVersion("es67x"),
      securityType = SecurityType.RorSecurity(
        ReadonlyRestPlugin.Config.Attributes.default.copy(
          rorConfigFileName = RemoteReindexSuite.this.sourceEsRorConfigFileName,
          restSsl = Enabled.No
        ))
    )
  )

  private lazy val destEsCluster = {
    def clusterSettingsCreator(securityType: SecurityType) = EsClusterSettings.create(
      clusterName = "ROR_DEST_ES",
      containerSpecification = ContainerSpecification(
        environmentVariables = Map.empty,
        additionalElasticsearchYamlEntries = Map("reindex.remote.whitelist" -> "\"*:9200\"")
      ),
      securityType = securityType
    )
    createLocalClusterContainer(
      clusterSettingsCreator {
        SecurityType.RorWithXpackSecurity(
          ReadonlyRestWithEnabledXpackSecurityPlugin.Config.Attributes.default.copy(
            rorConfigFileName = RemoteReindexSuite.this.rorConfigFileName,
            restSsl = Enabled.No
          )
        )
      }
    )
  }

  private lazy val destEsIndexManager = new IndexManager(clients.head.basicAuthClient("dev1", "test"), esVersionUsed)

  private lazy val sourceEsContainer = sourceEsCluster.nodes.head

  lazy val clusterContainers: NonEmptyList[EsClusterContainer] = NonEmptyList.of(destEsCluster, sourceEsCluster)
  lazy val esTargets: NonEmptyList[EsContainer] = NonEmptyList.of(destEsCluster.nodes.head, sourceEsCluster.nodes.head)

  "A remote reindex request" should {
    "be able to proceed" when {
      "request specifies source and dest index that are allowed on both source and dest ES" in {
        val result = destEsIndexManager.reindex(createReindexSource("test1_index", "dev1"), "test1_index_reindexed")

        result should have statusCode 200
      }
    }
    "be blocked by dest ES" when {
      "request specifies source index that is allowed, but dest that isn't allowed" in {
        val result = destEsIndexManager.reindex(createReindexSource("test1_index", "dev1"), "not_allowed_index")

        result should have statusCode 403
      }
      "request specifies source index that isn't allowed, but dest that is allowed" in {
        val result = destEsIndexManager.reindex(createReindexSource("not_allowed_index", "dev1"), "test1_index_reindexed")

        result should have statusCode 403
      }
      "request specifies both source index and dest index that are not allowed" in {
        val result = destEsIndexManager.reindex(createReindexSource("not_allowed_index", "dev1"), "not_allowed_index_reindexed")

        result should have statusCode 403
      }
    }
    "be blocked by source ES" when {
      "request specifies source index that is allowed on dest ES, but is not allowed on source ES" in {
        val result = destEsIndexManager.reindex(createReindexSource("test1_index", "dev3"), "test1_index_reindexed")

        result should have statusCode 404
      }
      "request specifies index which is allowed, but is not present in source ES" in {
        val result = destEsIndexManager.reindex(createReindexSource("test2_index", "dev4"), "test2_index_reindexed")

        result should have statusCode 404
      }
    }
  }

  private def createReindexSource(sourceIndex: String, username: String): ReindexSource.Remote = {
    ReindexSource.Remote(
      indexName = sourceIndex,
      address = s"http://${sourceEsContainer.getAddressInInternalNetwork}",
      username = username,
      password = "test"
    )
  }
}

object RemoteReindexSuite {
  private def sourceEsDataInitializer(): ElasticsearchNodeDataInitializer = {
    (esVersion: String, adminRestClient: RestClient) => {
      val documentManager = new DocumentManager(adminRestClient, esVersion)
      documentManager.createDoc("test1_index", "Sometype", 1, ujson.read("""{"hello":"world"}""")).force()
    }
  }
}
