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
import org.junit.Assert.assertEquals
import org.scalatest.{BeforeAndAfterEach, WordSpec}
import tech.beshu.ror.integration.suites.base.support.{BaseManyEsClustersIntegrationTest, MultipleClientsSupport}
import tech.beshu.ror.utils.containers.EsClusterSettings.EsVersion
import tech.beshu.ror.utils.containers._
import tech.beshu.ror.utils.elasticsearch.{ActionManagerJ, DocumentManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait ReIndexMultipleDifferentEsSuite extends WordSpec
  with BaseManyEsClustersIntegrationTest
  with MultipleClientsSupport
  with BeforeAndAfterEach {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/reindex_multi_containers/readonlyrest_dest_es.yml"
  private val sourceEsRorConfigFileName = "/reindex_multi_containers/readonlyrest_source_es.yml"

  private lazy val oldEsCluster = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR_OLD_ES",
      nodeDataInitializer = nodeDataInitializer(),
      xPackSupport = false,
      esVersion = EsVersion.SpecificVersion("es55x"),
      configHotReloadingEnabled = false
    )(sourceEsRorConfigFileName)
  )

  private lazy val newEsCluster = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR_NEW_ES",
      rorContainerSpecification = ContainerSpecification(
        Map(
          "reindex.remote.whitelist" -> "*:9200",
          "reindex.ssl.verification_mode" -> "none"
        )),
      xPackSupport = false,
      configHotReloadingEnabled = false
    )(rorConfigFileName)
  )

  private lazy val oldEsActionManager = new ActionManagerJ(clients.head.basicAuthClient("dev1", "test"))
  private lazy val newEsActionManager = new ActionManagerJ(clients.last.basicAuthClient("dev1", "test"))

  private lazy val oldEsContainer = oldEsCluster.nodes.head
  private lazy val newEsContainer = newEsCluster.nodes.head

  private lazy val oldEsContainerAddress = s"https://${oldEsContainer.containerInfo.getConfig.getHostName}:9200"

  lazy val clusterContainers: NonEmptyList[EsClusterContainer] = NonEmptyList.of(oldEsCluster, newEsCluster)
  lazy val esTargets: NonEmptyList[EsContainer] = NonEmptyList.of(oldEsCluster.nodes.head, newEsCluster.nodes.head)

  "A remote reindex request" should {
    "be able to proceed" when {
      "request specifies source and dest index that is allowed on both source and dest ES" in {
        val result = newEsActionManager.actionPost("_reindex", ReIndexMultipleDifferentEsSuite.reindexPayload("test1_index", "test1_index_reindexed", "dev1", oldEsContainerAddress))
        assertEquals(200, result.getResponseCode)
      }
    }
    "be blocked by dest ES" when {
      "request specifies source index that is allowed, but dest that isn't allowed" in {
        val result = newEsActionManager.actionPost("_reindex", ReIndexMultipleDifferentEsSuite.reindexPayload("test1_index", "not_allowed_index","dev1", oldEsContainerAddress))
        assertEquals(401, result.getResponseCode)
      }
      "request specifies source index that isn't allowed, but dest that is allowed" in {
        val result = newEsActionManager.actionPost("_reindex", ReIndexMultipleDifferentEsSuite.reindexPayload("not_allowed_index", "test1_index_reindexed","dev1", oldEsContainerAddress))
        assertEquals(401, result.getResponseCode)
      }
    }
    "be blocked by source ES" when {
      "request specifies source index that is allowed on dest ES, but is not allowed on source ES" in {
        val result = newEsActionManager.actionPost("_reindex", ReIndexMultipleDifferentEsSuite.reindexPayload("test1_index", "test1_index_reindexed","dev3", oldEsContainerAddress))
        assertEquals(401, result.getResponseCode)
      }
      "request specifies index which is allowed, but is not present in source ES"  in {
        val result = newEsActionManager.actionPost("_reindex", ReIndexMultipleDifferentEsSuite.reindexPayload("test2_index", "test2_index_reindexed","dev4", oldEsContainerAddress))
        assertEquals(404, result.getResponseCode)
      }
    }
  }

  protected def nodeDataInitializer(): ElasticsearchNodeDataInitializer = {
    (esVersion: String, adminRestClient: RestClient) => {
      val documentManager = new DocumentManager(adminRestClient, esVersion)
      documentManager.createDoc("test1_index", "Sometype", 1, ujson.read("""{"hello":"world"}"""))
    }
  }
}

object ReIndexMultipleDifferentEsSuite {
  private def reindexPayload(sourceIndexName: String, destIndexName: String, username: String, sourceHost: String) = {
    s"""
       |{
       |	"source": {
       |		"index": "$sourceIndexName",
       |    "type": "Sometype",
       |		"remote": {
       |			"host": "${sourceHost}",
       |			"username": "$username",
       |			"password": "test"
       |		}
       |	},
       |	"dest": {
       |		"index": "$destIndexName"
       |	}
       |}""".stripMargin
  }
}