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
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.{BaseManyEsClustersIntegrationTest, MultipleClientsSupport}
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsClusterContainer, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, IndexManager, RorApiManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.Resources.getResourceContent

import scala.concurrent.duration._
import scala.language.postfixOps

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

  private lazy val ror1WithIndexConfigAdminActionManager = new RorApiManager(clients.head.adminClient, esVersionUsed)
  private lazy val rorWithNoIndexConfigAdminActionManager = new RorApiManager(clients.last.adminClient, esVersionUsed)

  override lazy val esTargets = NonEmptyList.of(ror1_1Node, ror1_2Node, ror2_1Node)
  override lazy val clusterContainers = NonEmptyList.of(rorWithIndexConfig, rorWithNoIndexConfig)

  "An admin REST API" should {
    "provide a method for force refresh ROR config" which {
      "is going to reload ROR core" when {
        "in-index config is newer than current one" in {
          val rorApiManager = new RorApiManager(ror2_1Node.adminClient, esVersionUsed)
          rorApiManager
            .insertInIndexConfigDirectlyToRorIndex(
              rorConfigIndex = readonlyrestIndexName,
              config = getResourceContent("/admin_api/readonlyrest_index.yml")
            )
            .force()

          val result = rorWithNoIndexConfigAdminActionManager.reloadRorConfig()

          result.responseCode should be(200)
          result.responseJson("status").str should be("ok")
          result.responseJson("message").str should be("ReadonlyREST settings were reloaded with success!")
        }
      }
      "return info that config is up to date" when {
        "in-index config is the same as current one" in {
          val rorApiManager = new RorApiManager(ror2_1Node.adminClient, esVersionUsed)
          rorApiManager
            .insertInIndexConfigDirectlyToRorIndex(
              rorConfigIndex = readonlyrestIndexName,
              config = getResourceContent("/admin_api/readonlyrest.yml")
            )
            .force()

          val result = rorWithNoIndexConfigAdminActionManager.reloadRorConfig()

          result.responseCode should be(200)
          result.responseJson("status").str should be("ko")
          result.responseJson("message").str should be("Current settings are already loaded")
        }
      }
      "return info that in-index config does not exist" when {
        "there is no in-index settings configured yet" in {
          val result = rorWithNoIndexConfigAdminActionManager.reloadRorConfig()
          result.responseCode should be(200)
          result.responseJson("status").str should be("ko")
          result.responseJson("message").str should be("Cannot find settings index")
        }
      }
      "return info that cannot reload config" when {
        "config cannot be reloaded (eg. because LDAP is not achievable)" in {
          val rorApiManager = new RorApiManager(ror2_1Node.adminClient, esVersionUsed)
          rorApiManager
            .insertInIndexConfigDirectlyToRorIndex(
              rorConfigIndex = readonlyrestIndexName,
              config = getResourceContent("/admin_api/readonlyrest_with_ldap.yml")
            )
            .force()

          val result = rorWithNoIndexConfigAdminActionManager.reloadRorConfig()
          result.responseCode should be(200)
          result.responseJson("status").str should be("ko")
          result.responseJson("message").str should be("Cannot reload new settings: Errors:\nThere was a problem with LDAP connection to: ldap://localhost:389")
        }
      }
    }
    "provide a method for update in-index config" which {
      "is going to reload ROR core and store new in-index config" when {
        "configuration is new and correct" in {
          def forceReload(rorSettingsResource: String) = {
            val result = ror1WithIndexConfigAdminActionManager.updateRorInIndexConfig(getResourceContent(rorSettingsResource))

            result.responseCode should be(200)
            result.responseJson("status").str should be("ok")
            result.responseJson("message").str should be("updated settings")
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
          val result = ror1WithIndexConfigAdminActionManager
            .updateRorInIndexConfig(getResourceContent("/admin_api/readonlyrest_index.yml"))

          result.responseCode should be(200)
          result.responseJson("status").str should be("ko")
          result.responseJson("message").str should be("Current settings are already loaded")
        }
      }
      "return info that config is malformed" when {
        "invalid YAML is provided" in {
          val result = ror1WithIndexConfigAdminActionManager
            .updateRorInIndexConfig(getResourceContent("/admin_api/readonlyrest_malformed.yml"))

          result.responseCode should be(200)
          result.responseJson("status").str should be("ko")
          result.responseJson("message").str should startWith("Settings content is malformed")
        }
      }
      "return info that cannot reload" when {
        "ROR core cannot be reloaded" in {
          val result = ror1WithIndexConfigAdminActionManager
            .updateRorInIndexConfig(getResourceContent("/admin_api/readonlyrest_with_ldap.yml"))

          result.responseCode should be(200)
          result.responseJson("status").str should be("ko")
          result.responseJson("message").str should be("Cannot reload new settings: Errors:\nThere was a problem with LDAP connection to: ldap://localhost:389")
        }
      }
    }
    "provide a method for fetching current in-index config" which {
      "return current config" when {
        "there is one in index" in {
          val getIndexConfigResult = ror1WithIndexConfigAdminActionManager.getRorInIndexConfig

          getIndexConfigResult.responseCode should be(200)
          getIndexConfigResult.responseJson("status").str should be("ok")
          getIndexConfigResult.responseJson("message").str should be {
            getResourceContent("/admin_api/readonlyrest_index.yml")
          }
        }
      }
      "return info that there is no in-index config" when {
        "there is none in index" in {
          val getIndexConfigResult = rorWithNoIndexConfigAdminActionManager.getRorInIndexConfig

          getIndexConfigResult.responseCode should be(200)
          getIndexConfigResult.responseJson("status").str should be("empty")
          getIndexConfigResult.responseJson("message").str should be {
            "Cannot find settings index"
          }
        }
      }
    }
    "provide a method for fetching current file config" which {
      "return current config" in {
        val result = ror1WithIndexConfigAdminActionManager.getRorFileConfig

        result.responseCode should be(200)
        result.responseJson("status").str should be("ok")
        result.responseJson("message").str should be {
          getResourceContent("/admin_api/readonlyrest.yml")
        }
      }
    }
    "provide a method for reload test config engine" which {
      "is going to reload ROR test core" when {
        "configuration is new and correct" in {
          def forceReload(rorSettingsResource: String) = {
            val result = ror1WithIndexConfigAdminActionManager.updateRorTestConfig(getResourceContent(rorSettingsResource))

            result.responseCode should be(200)
            result.responseJson("status").str should be("ok")
            result.responseJson("message").str should be("updated settings")
          }

          val dev1Ror1stInstanceSearchManager = new SearchManager(
            clients.head.basicAuthClient("admin1", "pass"),
            Map("IMPERSONATE_AS" -> "dev1")
          )
          val dev2Ror1stInstanceSearchManager = new SearchManager(
            clients.head.basicAuthClient("admin1", "pass"),
            Map("IMPERSONATE_AS" -> "dev2")
          )
          val dev1Ror2ndInstanceSearchManager = new SearchManager(
            clients.head.basicAuthClient("admin1", "pass"),
            Map("IMPERSONATE_AS" -> "dev1")
          )
          val dev2Ror2ndInstanceSearchManager = new SearchManager(
            clients.head.basicAuthClient("admin1", "pass"),
            Map("IMPERSONATE_AS" -> "dev2")
          )

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
          forceReload("/admin_api/readonlyrest_first_update_with_impersonation.yml")

          // after first reload only dev1 can access indices
          val dev1ror1After1stReloadResults = dev1Ror1stInstanceSearchManager.search("test1_index")
          dev1ror1After1stReloadResults.responseCode should be(200)
          val dev2ror1After1stReloadResults = dev2Ror1stInstanceSearchManager.search("test2_index")
          dev2ror1After1stReloadResults.responseCode should be(401)
          val dev1ror2After1stReloadResults = dev1Ror2ndInstanceSearchManager.search("test1_index")
          dev1ror2After1stReloadResults.responseCode should be(200)
          val dev2ror2After1stReloadResults = dev2Ror2ndInstanceSearchManager.search("test2_index")
          dev2ror2After1stReloadResults.responseCode should be(401)

          // second reload
          forceReload("/admin_api/readonlyrest_second_update_with_impersonation.yml")

          // after second reload dev1 & dev2 can access indices
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
      "is going to reload ROR test core with TTL" when {
        "configuration is new and correct" in {
          def forceReload(rorSettingsResource: String, ttl: Option[FiniteDuration] = None) = {
            val result = ror1WithIndexConfigAdminActionManager.updateRorTestConfig(
              getResourceContent(rorSettingsResource),
              ttl
            )

            result.responseCode should be(200)
            result.responseJson("status").str should be("ok")
            result.responseJson("message").str should be("updated settings")
          }

          val dev1Ror1stInstanceSearchManager = new SearchManager(
            clients.head.basicAuthClient("admin1", "pass"),
            Map("IMPERSONATE_AS" -> "dev1")
          )
          val dev2Ror1stInstanceSearchManager = new SearchManager(
            clients.head.basicAuthClient("admin1", "pass"),
            Map("IMPERSONATE_AS" -> "dev2")
          )
          val dev1Ror2ndInstanceSearchManager = new SearchManager(
            clients.head.basicAuthClient("admin1", "pass"),
            Map("IMPERSONATE_AS" -> "dev1")
          )
          val dev2Ror2ndInstanceSearchManager = new SearchManager(
            clients.head.basicAuthClient("admin1", "pass"),
            Map("IMPERSONATE_AS" -> "dev2")
          )

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
          forceReload("/admin_api/readonlyrest_first_update_with_impersonation.yml")

          // after first reload only dev1 can access indices
          val dev1ror1After1stReloadResults = dev1Ror1stInstanceSearchManager.search("test1_index")
          dev1ror1After1stReloadResults.responseCode should be(200)
          val dev2ror1After1stReloadResults = dev2Ror1stInstanceSearchManager.search("test2_index")
          dev2ror1After1stReloadResults.responseCode should be(401)
          val dev1ror2After1stReloadResults = dev1Ror2ndInstanceSearchManager.search("test1_index")
          dev1ror2After1stReloadResults.responseCode should be(200)
          val dev2ror2After1stReloadResults = dev2Ror2ndInstanceSearchManager.search("test2_index")
          dev2ror2After1stReloadResults.responseCode should be(401)

          // second reload
          forceReload(
            "/admin_api/readonlyrest_second_update_with_impersonation.yml",
            ttl = Some(3 seconds)
          )

          // after second reload dev1 & dev2 can access indices
          val dev1ror1After2ndReloadResults = dev1Ror1stInstanceSearchManager.search("test1_index")
          dev1ror1After2ndReloadResults.responseCode should be(200)
          val dev2ror1After2ndReloadResults = dev2Ror1stInstanceSearchManager.search("test2_index")
          dev2ror1After2ndReloadResults.responseCode should be(200)
          val dev1ror2After2ndReloadResults = dev1Ror2ndInstanceSearchManager.search("test1_index")
          dev1ror2After2ndReloadResults.responseCode should be(200)
          val dev2ror2After2ndReloadResults = dev2Ror2ndInstanceSearchManager.search("test2_index")
          dev2ror2After2ndReloadResults.responseCode should be(200)

          // wait for test engine auto-destruction
          Thread.sleep(3000)
          val dev1ror1AfterTestEngineAutoDestruction = dev1Ror1stInstanceSearchManager.search("test1_index")
          dev1ror1AfterTestEngineAutoDestruction.responseCode should be(401)
          val dev2ror1AfterTestEngineAutoDestruction = dev2Ror1stInstanceSearchManager.search("test2_index")
          dev2ror1AfterTestEngineAutoDestruction.responseCode should be(401)
          val dev1ror2AfterTestEngineAutoDestruction = dev1Ror2ndInstanceSearchManager.search("test1_index")
          dev1ror2AfterTestEngineAutoDestruction.responseCode should be(401)
          val dev2ror2AfterTestEngineAutoDestruction = dev2Ror2ndInstanceSearchManager.search("test2_index")
          dev2ror2AfterTestEngineAutoDestruction.responseCode should be(401)
        }
      }
      "return info that config is up to date" when {
        "test config is the same as provided one" in {
          ror1WithIndexConfigAdminActionManager
            .updateRorTestConfig(getResourceContent("/admin_api/readonlyrest_index.yml"))
            .force()

          val result = ror1WithIndexConfigAdminActionManager
            .updateRorTestConfig(getResourceContent("/admin_api/readonlyrest_index.yml"))
            .force()

          result.responseCode should be(200)
          result.responseJson("status").str should be("ko")
          result.responseJson("message").str should be("Current settings are already loaded")
        }
      }
      "return info that config is malformed" when {
        "invalid YAML is provided" in {
          val result = ror1WithIndexConfigAdminActionManager
            .updateRorTestConfig(getResourceContent("/admin_api/readonlyrest_malformed.yml"))

          result.responseCode should be(200)
          result.responseJson("status").str should be("ko")
          result.responseJson("message").str should startWith("Settings content is malformed")
        }
      }
      "return info that cannot reload" when {
        "ROR core cannot be reloaded" in {
          val result = ror1WithIndexConfigAdminActionManager
            .updateRorTestConfig(getResourceContent("/admin_api/readonlyrest_with_ldap.yml"))

          result.responseCode should be(200)
          result.responseJson("status").str should be("ko")
          result.responseJson("message").str should be("Cannot reload new settings: Errors:\nThere was a problem with LDAP connection to: ldap://localhost:389")
        }
      }
    }
    "provide a method for test config engine invalidation" which {
      "will destruct the engine on demand" in {
        def forceReload(rorSettingsResource: String) = {
          val result = ror1WithIndexConfigAdminActionManager.updateRorTestConfig(
            getResourceContent(rorSettingsResource)
          )

          result.responseCode should be(200)
          result.responseJson("status").str should be("ok")
          result.responseJson("message").str should be("updated settings")
        }

        val dev1Ror1stInstanceSearchManager = new SearchManager(
          clients.head.basicAuthClient("admin1", "pass"),
          Map("IMPERSONATE_AS" -> "dev1")
        )
        val dev2Ror1stInstanceSearchManager = new SearchManager(
          clients.head.basicAuthClient("admin1", "pass"),
          Map("IMPERSONATE_AS" -> "dev2")
        )
        val dev1Ror2ndInstanceSearchManager = new SearchManager(
          clients.head.basicAuthClient("admin1", "pass"),
          Map("IMPERSONATE_AS" -> "dev1")
        )
        val dev2Ror2ndInstanceSearchManager = new SearchManager(
          clients.head.basicAuthClient("admin1", "pass"),
          Map("IMPERSONATE_AS" -> "dev2")
        )

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
        forceReload("/admin_api/readonlyrest_first_update_with_impersonation.yml")

        // after first reload only dev1 can access indices
        val dev1ror1After1stReloadResults = dev1Ror1stInstanceSearchManager.search("test1_index")
        dev1ror1After1stReloadResults.responseCode should be(200)
        val dev2ror1After1stReloadResults = dev2Ror1stInstanceSearchManager.search("test2_index")
        dev2ror1After1stReloadResults.responseCode should be(401)
        val dev1ror2After1stReloadResults = dev1Ror2ndInstanceSearchManager.search("test1_index")
        dev1ror2After1stReloadResults.responseCode should be(200)
        val dev2ror2After1stReloadResults = dev2Ror2ndInstanceSearchManager.search("test2_index")
        dev2ror2After1stReloadResults.responseCode should be(401)

        // second reload
        forceReload("/admin_api/readonlyrest_second_update_with_impersonation.yml")

        // after second reload dev1 & dev2 can access indices
        val dev1ror1After2ndReloadResults = dev1Ror1stInstanceSearchManager.search("test1_index")
        dev1ror1After2ndReloadResults.responseCode should be(200)
        val dev2ror1After2ndReloadResults = dev2Ror1stInstanceSearchManager.search("test2_index")
        dev2ror1After2ndReloadResults.responseCode should be(200)
        val dev1ror2After2ndReloadResults = dev1Ror2ndInstanceSearchManager.search("test1_index")
        dev1ror2After2ndReloadResults.responseCode should be(200)
        val dev2ror2After2ndReloadResults = dev2Ror2ndInstanceSearchManager.search("test2_index")
        dev2ror2After2ndReloadResults.responseCode should be(200)

        val result = ror1WithIndexConfigAdminActionManager.invalidateRorTestConfig()
        result.responseCode should be (200)

        // after test core invalidation, main core should handle these requests
        val dev1ror1AfterTestEngineAutoDestruction = dev1Ror1stInstanceSearchManager.search("test1_index")
        dev1ror1AfterTestEngineAutoDestruction.responseCode should be(401)
        val dev2ror1AfterTestEngineAutoDestruction = dev2Ror1stInstanceSearchManager.search("test2_index")
        dev2ror1AfterTestEngineAutoDestruction.responseCode should be(401)
        val dev1ror2AfterTestEngineAutoDestruction = dev1Ror2ndInstanceSearchManager.search("test1_index")
        dev1ror2AfterTestEngineAutoDestruction.responseCode should be(401)
        val dev2ror2AfterTestEngineAutoDestruction = dev2Ror2ndInstanceSearchManager.search("test2_index")
        dev2ror2AfterTestEngineAutoDestruction.responseCode should be(401)
      }
    }
  }

  override protected def beforeEach(): Unit = {
    // back to configuration loaded on container start
    rorWithNoIndexConfigAdminActionManager
      .updateRorInIndexConfig(getResourceContent("/admin_api/readonlyrest.yml"))
      .force()

    rorWithNoIndexConfigAdminActionManager
      .invalidateRorTestConfig()
      .force()

    val indexManager = new IndexManager(ror2_1Node.adminClient, esVersionUsed)
    indexManager.removeIndex(readonlyrestIndexName)

    ror1WithIndexConfigAdminActionManager
      .updateRorInIndexConfig(getResourceContent("/admin_api/readonlyrest_index.yml"))
      .force()

    ror1WithIndexConfigAdminActionManager
      .invalidateRorTestConfig()
      .force()
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
    }
  }

}
