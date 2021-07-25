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
package tech.beshu.ror.integration.suites.base

import cats.data.NonEmptyList
import org.apache.commons.lang.StringEscapeUtils.escapeJava
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.{BaseManyEsClustersIntegrationTest, MultipleClientsSupport}
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsClusterContainer, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{ActionManagerJ, DocumentManager, IndexManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.Resources.getResourceContent

trait BaseAdminApiSuite
  extends AnyWordSpec
    with BaseManyEsClustersIntegrationTest
    with MultipleClientsSupport
    with BeforeAndAfterEach {
  this: EsContainerCreator =>

  protected def readonlyrestIndexName: String

  protected def rorWithIndexConfig: EsClusterContainer

  protected def rorWithNoIndexConfig: EsClusterContainer

  private lazy val ror1_1Node = rorWithIndexConfig.nodes.head
  private lazy val ror1_2Node = rorWithIndexConfig.nodes.tail.head
  private lazy val ror2_1Node = rorWithNoIndexConfig.nodes.head

  private lazy val ror1WithIndexConfigAdminActionManager = new ActionManagerJ(clients.head.adminClient)
  private lazy val rorWithNoIndexConfigAdminActionManager = new ActionManagerJ(clients.last.adminClient)

  override lazy val esTargets = NonEmptyList.of(ror1_1Node, ror1_2Node, ror2_1Node)
  override lazy val clusterContainers = NonEmptyList.of(rorWithIndexConfig, rorWithNoIndexConfig)

  "An admin REST API" should {
    "provide a method for force refresh ROR config" which {
      "is going to reload ROR core" when {
        "in-index config is newer than current one" in {
          insertInIndexConfig(
            new DocumentManager(ror2_1Node.adminClient, ror2_1Node.esVersion),
            "/admin_api/readonlyrest_index.yml"
          )

          val result = rorWithNoIndexConfigAdminActionManager.actionPost(
            "_readonlyrest/admin/refreshconfig", ""
          )
          result.getResponseCode should be(200)
          result.getResponseJsonMap.get("status") should be("ok")
          result.getResponseJsonMap.get("message") should be("ReadonlyREST settings were reloaded with success!")
        }
      }
      "return info that config is up to date" when {
        "in-index config is the same as current one" in {
          insertInIndexConfig(
            new DocumentManager(ror2_1Node.adminClient, ror2_1Node.esVersion),
            "/admin_api/readonlyrest.yml"
          )

          val result = rorWithNoIndexConfigAdminActionManager.actionPost(
            "_readonlyrest/admin/refreshconfig", ""
          )
          result.getResponseCode should be(200)
          result.getResponseJsonMap.get("status") should be("ko")
          result.getResponseJsonMap.get("message") should be("Current settings are already loaded")
        }
      }
      "return info that in-index config does not exist" when {
        "there is no in-index settings configured yet" in {
          val result = rorWithNoIndexConfigAdminActionManager.actionPost(
            "_readonlyrest/admin/refreshconfig", ""
          )
          result.getResponseCode should be(200)
          result.getResponseJsonMap.get("status") should be("ko")
          result.getResponseJsonMap.get("message") should be("Cannot find settings index")
        }
      }
      "return info that cannot reload config" when {
        "config cannot be reloaded (eg. because LDAP is not achievable)" in {
          insertInIndexConfig(
            new DocumentManager(ror2_1Node.adminClient, ror2_1Node.esVersion),
            "/admin_api/readonlyrest_with_ldap.yml"
          )

          val result = rorWithNoIndexConfigAdminActionManager.actionPost(
            "_readonlyrest/admin/refreshconfig", ""
          )
          result.getResponseCode should be(200)
          result.getResponseJsonMap.get("status") should be("ko")
          result.getResponseJsonMap.get("message") should be("Cannot reload new settings: Errors:\nThere was a problem with LDAP connection to: ldap://localhost:389")
        }
      }
    }
    "provide a method for update in-index config" which {
      "is going to reload ROR core and store new in-index config" when {
        "configuration is new and correct" in {
          def forceReload(rorSettingsResource: String) = {
            val result = ror1WithIndexConfigAdminActionManager.actionPost(
              "_readonlyrest/admin/config",
              s"""{"settings": "${escapeJava(getResourceContent(rorSettingsResource))}"}"""
            )
            result.getResponseCode should be(200)
            result.getResponseJsonMap.get("status") should be("ok")
            result.getResponseJsonMap.get("message") should be("updated settings")
          }

          val dev1Ror1stInstanceSearchManager = new SearchManager(clients.head.basicAuthClient("dev1", "test"))
          val dev2Ror1stInstanceSearchManager = new SearchManager(clients.head.basicAuthClient("dev2", "test"))
          val dev1Ror2ndInstanceSearchManager = new SearchManager(clients.tail.head.basicAuthClient("dev1", "test"))
          val dev2Ror2ndInstanceSearchManager = new SearchManager(clients.tail.head.basicAuthClient("dev2", "test"))

          // before first reload no user can access indices
          val dev1ror1Results = dev1Ror1stInstanceSearchManager.search("test1_index")
          dev1ror1Results.responseCode should be(401)
          val dev2ror1Results = dev2Ror1stInstanceSearchManager.search("test2_index")
          dev2ror1Results.responseCode should be(401)
          val dev1ror2Results = dev1Ror2ndInstanceSearchManager.search("test1_index")
          dev1ror2Results.responseCode should be(401)
          val dev2ror2Results = dev2Ror2ndInstanceSearchManager.search("test2_index")
          dev2ror2Results.responseCode should be(401)

          // first reload
          forceReload("/admin_api/readonlyrest_first_update.yml")

          // after first reload only dev1 can access indices
          Thread.sleep(14000) // have to wait for ROR1_2 instance config reload
          val dev1ror1After1stReloadResults = dev1Ror1stInstanceSearchManager.search("test1_index")
          dev1ror1After1stReloadResults.responseCode should be(200)
          val dev2ror1After1stReloadResults = dev2Ror1stInstanceSearchManager.search("test2_index")
          dev2ror1After1stReloadResults.responseCode should be(401)
          val dev1ror2After1stReloadResults = dev1Ror2ndInstanceSearchManager.search("test1_index")
          dev1ror2After1stReloadResults.responseCode should be(200)
          val dev2ror2After1stReloadResults = dev2Ror2ndInstanceSearchManager.search("test2_index")
          dev2ror2After1stReloadResults.responseCode should be(401)

          // second reload
          forceReload("/admin_api/readonlyrest_second_update.yml")

          // after second reload dev1 & dev2 can access indices
          Thread.sleep(7000) // have to wait for ROR1_2 instance config reload
          val dev1ror1After2ndReloadResults = dev1Ror1stInstanceSearchManager.search("test1_index")
          dev1ror1After2ndReloadResults.responseCode should be(200)
          val dev2ror1After2ndReloadResults = dev2Ror1stInstanceSearchManager.search("test2_index")
          dev2ror1After2ndReloadResults.responseCode should be(200)
          val dev1ror2After2ndReloadResults = dev1Ror2ndInstanceSearchManager.search("test1_index")
          dev1ror2After2ndReloadResults.responseCode should be(200)
          val dev2ror2After2ndReloadResults = dev2Ror2ndInstanceSearchManager.search("test2_index")
          dev2ror2After2ndReloadResults.responseCode should be(200)

        }
      }
      "return info that config is up to date" when {
        "in-index config is the same as provided one" in {
          val result = ror1WithIndexConfigAdminActionManager.actionPost(
            "_readonlyrest/admin/config",
            s"""{"settings": "${escapeJava(getResourceContent("/admin_api/readonlyrest_index.yml"))}"}"""
          )
          result.getResponseCode should be(200)
          result.getResponseJsonMap.get("status") should be("ko")
          result.getResponseJsonMap.get("message") should be("Current settings are already loaded")
        }
      }
      "return info that config is malformed" when {
        "invalid JSON is provided" in {
          val result = ror1WithIndexConfigAdminActionManager.actionPost(
            "_readonlyrest/admin/config",
            s"${escapeJava(getResourceContent("/admin_api/readonlyrest_first_update.yml"))}"
          )
          result.getResponseCode should be(200)
          result.getResponseJsonMap.get("status") should be("ko")
          result.getResponseJsonMap.get("message") should be("JSON body malformed")
        }
      }
      "return info that cannot reload" when {
        "ROR core cannot be reloaded" in {
          val result = ror1WithIndexConfigAdminActionManager.actionPost(
            "_readonlyrest/admin/config",
            s"""{"settings": "${escapeJava(getResourceContent("/admin_api/readonlyrest_with_ldap.yml"))}"}"""
          )
          result.getResponseCode should be(200)
          result.getResponseJsonMap.get("status") should be("ko")
          result.getResponseJsonMap.get("message") should be("Cannot reload new settings: Errors:\nThere was a problem with LDAP connection to: ldap://localhost:389")
        }
      }
    }
    "provide a method for fetching current in-index config" which {
      "return current config" when {
        "there is one in index" in {
          val getIndexConfigResult = ror1WithIndexConfigAdminActionManager.actionGet("_readonlyrest/admin/config")
          getIndexConfigResult.getResponseCode should be(200)
          getIndexConfigResult.getResponseJsonMap.get("status") should be("ok")
          getIndexConfigResult.getResponseJsonMap.get("message").asInstanceOf[String] should be {
            getResourceContent("/admin_api/readonlyrest_index.yml")
          }
        }
      }
      "return info that there is no in-index config" when {
        "there is none in index" in {
          val getIndexConfigResult = rorWithNoIndexConfigAdminActionManager.actionGet("_readonlyrest/admin/config")
          getIndexConfigResult.getResponseCode should be(200)
          getIndexConfigResult.getResponseJsonMap.get("status") should be("empty")
          getIndexConfigResult.getResponseJsonMap.get("message").asInstanceOf[String] should be {
            "Cannot find settings index"
          }
        }
      }
    }
    "provide a method for fetching current file config" which {
      "return current config" in {
        val result = ror1WithIndexConfigAdminActionManager.actionGet("_readonlyrest/admin/config/file")
        result.getResponseCode should be(200)
        result.getResponseJsonMap.get("status") should be("ok")
        result.getResponseJsonMap.get("message").asInstanceOf[String] should be {
          getResourceContent("/admin_api/readonlyrest.yml")
        }
      }
    }
  }

  override protected def beforeEach(): Unit = {
    // back to configuration loaded on container start
    rorWithNoIndexConfigAdminActionManager.actionPost(
      "_readonlyrest/admin/config",
      s"""{"settings": "${escapeJava(getResourceContent("/admin_api/readonlyrest.yml"))}"}"""
    )
    val indexManager = new IndexManager(ror2_1Node.adminClient)
    indexManager.removeIndex(readonlyrestIndexName)

    ror1WithIndexConfigAdminActionManager.actionPost(
      "_readonlyrest/admin/config",
      s"""{"settings": "${escapeJava(getResourceContent("/admin_api/readonlyrest_index.yml"))}"}"""
    )
  }

  protected def nodeDataInitializer(): ElasticsearchNodeDataInitializer = {
    (esVersion: String, adminRestClient: RestClient) => {
      val documentManager = new DocumentManager(adminRestClient, esVersion)
      documentManager
        .createDoc("test1_index", 1, ujson.read("""{"hello":"world"}"""))
        .force()
      documentManager
        .createDoc("test2_index", 1, ujson.read("""{"hello":"world"}"""))
        .force()
      insertInIndexConfig(documentManager, "/admin_api/readonlyrest_index.yml")
    }
  }

  private def insertInIndexConfig(documentManager: DocumentManager, resourceFilePath: String): Unit = {
    documentManager
      .createDoc(
        readonlyrestIndexName,
        "settings",
        id = 1,
        ujson.read(s"""{"settings": "${escapeJava(getResourceContent(resourceFilePath))}"}""")
      )
      .force()
  }
}
