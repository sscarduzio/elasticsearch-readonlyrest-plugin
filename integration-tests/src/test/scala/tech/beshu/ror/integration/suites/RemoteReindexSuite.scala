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

trait RemoteReindexSuite extends WordSpec
  with BaseManyEsClustersIntegrationTest
  with MultipleClientsSupport
  with BeforeAndAfterEach {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/reindex_multi_containers/readonlyrest_dest_es.yml"
  private val sourceEsRorConfigFileName = "/reindex_multi_containers/readonlyrest_source_es.yml"

  private lazy val sourceEsCluster = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR_SOURCE_ES",
      nodeDataInitializer = sourceEsDataInitializer(),
      xPackSupport = false,
      esVersion = EsVersion.SpecificVersion("es55x")
    )(sourceEsRorConfigFileName)
  )

  private lazy val destEsCluster = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR_DEST_ES",
      nodeDataInitializer = destEsDataInitializer(),
      rorContainerSpecification = ContainerSpecification(
        Map(
          "reindex.remote.whitelist" -> "*:9200",
          "reindex.ssl.verification_mode" -> "none"
        )),
      xPackSupport = false
    )(rorConfigFileName)
  )

  private lazy val sourceEsActionManager = new ActionManagerJ(clients.head.basicAuthClient("dev1", "test"))
  private lazy val destEsActionManager = new ActionManagerJ(clients.last.basicAuthClient("dev1", "test"))

  private lazy val sourceEsContainer = sourceEsCluster.nodes.head
  private lazy val destEsContainer = destEsCluster.nodes.head

  private lazy val oldEsContainerAddress = s"https://${sourceEsContainer.containerInfo.getConfig.getHostName}:9200"

  lazy val clusterContainers: NonEmptyList[EsClusterContainer] = NonEmptyList.of(sourceEsCluster, destEsCluster)
  lazy val esTargets: NonEmptyList[EsContainer] = NonEmptyList.of(sourceEsCluster.nodes.head, destEsCluster.nodes.head)

  "A remote reindex request" should {
    "be able to proceed" when {
      "request specifies source and dest index that are allowed on both source and dest ES" in {
        val result = destEsActionManager.actionPost("_reindex", RemoteReindexSuite.remoteReindexPayload("test1_index", "test1_index_reindexed", "dev1", oldEsContainerAddress))
        assertEquals(200, result.getResponseCode)
      }
    }
    "be blocked by dest ES" when {
      "request specifies source index that is allowed, but dest that isn't allowed" in {
        val result = destEsActionManager.actionPost("_reindex", RemoteReindexSuite.remoteReindexPayload("test1_index", "not_allowed_index","dev1", oldEsContainerAddress))
        assertEquals(401, result.getResponseCode)
      }
      "request specifies source index that isn't allowed, but dest that is allowed" in {
        val result = destEsActionManager.actionPost("_reindex", RemoteReindexSuite.remoteReindexPayload("not_allowed_index", "test1_index_reindexed","dev1", oldEsContainerAddress))
        assertEquals(401, result.getResponseCode)
      }
      "request specifies both source index and dest index that are not allowed" in {
        val result = destEsActionManager.actionPost("_reindex", RemoteReindexSuite.remoteReindexPayload("not_allowed_index", "not_allowed_index_reindexed","dev1", oldEsContainerAddress))
        assertEquals(401, result.getResponseCode)
      }
    }
    "be blocked by source ES" when {
      "request specifies source index that is allowed on dest ES, but is not allowed on source ES" in {
        val result = destEsActionManager.actionPost("_reindex", RemoteReindexSuite.remoteReindexPayload("test1_index", "test1_index_reindexed","dev3", oldEsContainerAddress))
        assertEquals(401, result.getResponseCode)
      }
      "request specifies index which is allowed, but is not present in source ES"  in {
        val result = destEsActionManager.actionPost("_reindex", RemoteReindexSuite.remoteReindexPayload("test2_index", "test2_index_reindexed","dev4", oldEsContainerAddress))
        assertEquals(404, result.getResponseCode)
      }
    }
  }

  "A local reindex request on dest ES" should {
    "be able to proceed" when {
      "request specifies source and dest index that are allowed" in {
        val result = destEsActionManager.actionPost("_reindex", RemoteReindexSuite.localReindexPayload("test3_index", "test3_index_reindexed"))
        assertEquals(200, result.getResponseCode)
      }
    }
    "be blocked" when {
      "request specifies source index that is allowed, but dest that isn't allowed" in {
        val result = destEsActionManager.actionPost("_reindex", RemoteReindexSuite.localReindexPayload("test3_index", "not_allowed_index"))
        assertEquals(401, result.getResponseCode)
      }
      "request specifies source index that isn't allowed, but dest that is allowed" in {
        val result = destEsActionManager.actionPost("_reindex", RemoteReindexSuite.localReindexPayload("not_allowed_index", "test3_index_reindexed"))
        assertEquals(401, result.getResponseCode)
      }
      "request specifies both source index and dest index that are not allowed" in {
        val result = destEsActionManager.actionPost("_reindex", RemoteReindexSuite.localReindexPayload("not_allowed_index", "not_allowed_index_reindexed"))
        assertEquals(401, result.getResponseCode)
      }
    }
  }

  protected def sourceEsDataInitializer(): ElasticsearchNodeDataInitializer = {
    (esVersion: String, adminRestClient: RestClient) => {
      val documentManager = new DocumentManager(adminRestClient, esVersion)
      documentManager.createDoc("test1_index", "Sometype", 1, ujson.read("""{"hello":"world"}"""))
    }
  }

  protected def destEsDataInitializer(): ElasticsearchNodeDataInitializer = {
    (esVersion: String, adminRestClient: RestClient) => {
      val documentManager = new DocumentManager(adminRestClient, esVersion)
      documentManager.createDoc("test3_index", 1, ujson.read("""{"hello":"world"}"""))
    }
  }
}

object RemoteReindexSuite {
  private def remoteReindexPayload(sourceIndexName: String, destIndexName: String, username: String, sourceHost: String) = {
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

  private def localReindexPayload(sourceIndexName: String, destIndexName: String) = {
    s"""
       |{
       |	"source": {
       |		"index": "$sourceIndexName"
       |	},
       |	"dest": {
       |		"index": "$destIndexName"
       |	}
       |}""".stripMargin
  }
}