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

import java.time.Instant

import cats.data.NonEmptyList
import org.apache.commons.lang.StringEscapeUtils.escapeJava
import org.scalatest.BeforeAndAfterEach
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.{BaseManyEsClustersIntegrationTest, MultipleClientsSupport}
import tech.beshu.ror.integration.utils.ESVersionSupportForAnyWordSpecLike
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsClusterContainer, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, IndexManager, RorApiManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.Resources.getResourceContent
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Try

trait BaseAdminApiSuite
  extends AnyWordSpec
    with BaseManyEsClustersIntegrationTest
    with ESVersionSupportForAnyWordSpecLike
    with MultipleClientsSupport
    with BeforeAndAfterEach {
  this: EsContainerCreator =>

  protected def readonlyrestIndexName: String

  protected def rorWithIndexConfig: EsClusterContainer

  protected def rorWithNoIndexConfig: EsClusterContainer

  private lazy val ror1_1Node = rorWithIndexConfig.nodes.head
  private lazy val ror1_2Node = rorWithIndexConfig.nodes.tail.head
  private lazy val ror2_1Node = rorWithNoIndexConfig.nodes.head

  private lazy val ror1WithIndexConfigAdminActionManager = new RorApiManager(clients.head.rorAdminClient, esVersionUsed)
  private lazy val rorWithNoIndexConfigAdminActionManager = new RorApiManager(clients.last.rorAdminClient, esVersionUsed)

  override lazy val esTargets = NonEmptyList.of(ror1_1Node, ror1_2Node, ror2_1Node)
  override lazy val clusterContainers = NonEmptyList.of(rorWithIndexConfig, rorWithNoIndexConfig)

  "An admin REST API" should {
    "provide a method for force refresh ROR config" which {
      "is going to reload ROR core" when {
        "in-index config is newer than current one" in {
          val rorApiManager = new RorApiManager(ror2_1Node.rorAdminClient, esVersionUsed)
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
          val rorApiManager = new RorApiManager(ror2_1Node.rorAdminClient, esVersionUsed)
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
          val rorApiManager = new RorApiManager(ror2_1Node.rorAdminClient, esVersionUsed)
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
      "return info that request is malformed" when {
        "settings key missing" in {
          val result = ror1WithIndexConfigAdminActionManager
            .updateRorInIndexConfigRaw(rawRequestBody = "{}")

          result.responseCode should be(400)
          result.responseJson("status").str should be("ko")
          result.responseJson("message").str should be("JSON body malformed: [Could not parse at .settings: [Attempt to decode value on failed cursor: DownField(settings)]]")
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
    "provide a method for get current test settings" which {
      "return info that test settings are not configured" when {
        "get current test settings" in {
          val testConfigResponse = ror1WithIndexConfigAdminActionManager.currentRorTestConfig
          testConfigResponse.responseCode should be(200)
          testConfigResponse.responseJson("status").str should be("TEST_SETTINGS_NOT_CONFIGURED")
          testConfigResponse.responseJson("message").str should be("ROR Test settings are not configured")
        }
        "get local users" in {
          val localUsersResponse = ror1WithIndexConfigAdminActionManager.currentRorLocalUsers
          localUsersResponse.responseCode should be(200)
          localUsersResponse.responseJson("status").str should be("TEST_SETTINGS_NOT_CONFIGURED")
          localUsersResponse.responseJson("message").str should be("ROR Test settings are not configured")
        }
      }
      "return current test settings" when {
        "configuration is valid and response without warnings" in {
          def forceReload(rorSettingsResource: String) = {
            val testConfig = getResourceContent(rorSettingsResource)
            val result = ror1WithIndexConfigAdminActionManager.updateRorTestConfig(testConfig)

            result.responseCode should be(200)
            result.responseJson("status").str should be("OK")
            result.responseJson("message").str should be("updated settings")

            val testConfigResponse = ror1WithIndexConfigAdminActionManager.currentRorTestConfig
            testConfigResponse.responseCode should be(200)
            testConfigResponse.responseJson("status").str should be("TEST_SETTINGS_PRESENT")
            testConfigResponse.responseJson("settings").str should be(testConfig)
            testConfigResponse.responseJson("ttl").str.matches("""^(0|[1-9][0-9]*) minutes""") should be(true)
            isIsoDateTime(testConfigResponse.responseJson("valid_to").str) should be(true)
            testConfigResponse.responseJson("warnings").arr should be(empty)
          }

          val dev1Ror1stInstanceSearchManager = new SearchManager(
            clients.head.basicAuthClient("admin1", "pass"),
            Map("x-ror-impersonating" -> "dev1")
          )
          val dev2Ror1stInstanceSearchManager = new SearchManager(
            clients.head.basicAuthClient("admin1", "pass"),
            Map("x-ror-impersonating" -> "dev2")
          )
          val dev1Ror2ndInstanceSearchManager = new SearchManager(
            clients.head.basicAuthClient("admin1", "pass"),
            Map("x-ror-impersonating" -> "dev1")
          )
          val dev2Ror2ndInstanceSearchManager = new SearchManager(
            clients.head.basicAuthClient("admin1", "pass"),
            Map("x-ror-impersonating" -> "dev2")
          )

          // before first reload no user can access indices
          val dev1ror1Results = dev1Ror1stInstanceSearchManager.search("test1_index")
          dev1ror1Results.responseCode should be(403)
          dev1ror1Results.responseJson should be(testSettingsNotConfiguredResponse)
          val dev2ror1Results = dev2Ror1stInstanceSearchManager.search("test2_index")
          dev2ror1Results.responseCode should be(403)
          dev2ror1Results.responseJson should be(testSettingsNotConfiguredResponse)
          val dev1ror2Results = dev1Ror2ndInstanceSearchManager.search("test1_index")
          dev1ror2Results.responseCode should be(403)
          dev1ror2Results.responseJson should be(testSettingsNotConfiguredResponse)
          val dev2ror2Results = dev2Ror2ndInstanceSearchManager.search("test2_index")
          dev2ror2Results.responseCode should be(403)
          dev2ror2Results.responseJson should be(testSettingsNotConfiguredResponse)

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
        "configuration is valid and response with warnings" in {
          val dev1Ror1stInstanceSearchManager = new SearchManager(
            clients.head.basicAuthClient("admin1", "pass"),
            Map("x-ror-impersonating" -> "dev1")
          )

          // before first reload no user can access indices
          val dev1ror1Results = dev1Ror1stInstanceSearchManager.search("test1_index")
          dev1ror1Results.responseCode should be(403)
          dev1ror1Results.responseJson should be(testSettingsNotConfiguredResponse)

          val testConfig = getResourceContent("/admin_api/readonlyrest_with_warnings.yml")
          val result = ror1WithIndexConfigAdminActionManager.updateRorTestConfig(testConfig)

          result.responseCode should be(200)
          result.responseJson("status").str should be("OK")
          result.responseJson("message").str should be("updated settings")

          val testConfigResponse = ror1WithIndexConfigAdminActionManager.currentRorTestConfig
          testConfigResponse.responseCode should be(200)
          testConfigResponse.responseJson("status").str should be("TEST_SETTINGS_PRESENT")
          testConfigResponse.responseJson("settings").str should be(testConfig)
          testConfigResponse.responseJson("ttl").str.matches("""^(0|[1-9][0-9]*) minutes""") should be(true)
          isIsoDateTime(testConfigResponse.responseJson("valid_to").str) should be(true)
          testConfigResponse.responseJson("warnings").arr.size should be(1)
          testConfigResponse.responseJson("warnings")(0)("block_name").str should be("test1")
          testConfigResponse.responseJson("warnings")(0)("rule_name").str should be("auth_key_sha256")
          testConfigResponse.responseJson("warnings")(0)("message").str should be("The rule contains fully hashed username and password. It doesn't support impersonation in this configuration")
          testConfigResponse.responseJson("warnings")(0)("hint").str should be("You can use second version of the rule and use not hashed username. Like that: `auth_key_sha256: USER_NAME:hash(PASSWORD)")

          // user with hashed credential cannot be impersonated
          val dev1ror1After2ndReloadResults = dev1Ror1stInstanceSearchManager.search("test1_index")
          dev1ror1After2ndReloadResults.responseCode should be(401)
        }
        "return local users" in {
          val updateResult = ror1WithIndexConfigAdminActionManager.updateRorTestConfig(getResourceContent("/admin_api/readonlyrest_first_update_with_impersonation.yml"))
          updateResult.responseJson("status").str should be("OK")
          updateResult.responseJson("message").str should be("updated settings")

          val localUsersResponse = ror1WithIndexConfigAdminActionManager.currentRorLocalUsers
          localUsersResponse.responseJson("status").str should be("OK")
          localUsersResponse.responseJson("users").arr.toList.map(_.str) should be(List("admin", "dev1"))
          localUsersResponse.responseJson("unknown_users").bool should be(false)
        }
      }
      "return info that configuration was invalidated" in {
        def forceReload(rorSettingsResource: String) = {
          val result = ror1WithIndexConfigAdminActionManager.updateRorTestConfig(getResourceContent(rorSettingsResource))

          result.responseCode should be(200)
          result.responseJson("status").str should be("OK")
          result.responseJson("message").str should be("updated settings")
        }

        val dev1Ror1stInstanceSearchManager = new SearchManager(
          clients.head.basicAuthClient("admin1", "pass"),
          Map("x-ror-impersonating" -> "dev1")
        )
        val dev2Ror1stInstanceSearchManager = new SearchManager(
          clients.head.basicAuthClient("admin1", "pass"),
          Map("x-ror-impersonating" -> "dev2")
        )
        val dev1Ror2ndInstanceSearchManager = new SearchManager(
          clients.head.basicAuthClient("admin1", "pass"),
          Map("x-ror-impersonating" -> "dev1")
        )
        val dev2Ror2ndInstanceSearchManager = new SearchManager(
          clients.head.basicAuthClient("admin1", "pass"),
          Map("x-ror-impersonating" -> "dev2")
        )

        // before first reload no user can access indices
        val dev1ror1Results = dev1Ror1stInstanceSearchManager.search("test1_index")
        dev1ror1Results.responseCode should be(403)
        dev1ror1Results.responseJson should be(testSettingsNotConfiguredResponse)
        val dev2ror1Results = dev2Ror1stInstanceSearchManager.search("test2_index")
        dev2ror1Results.responseCode should be(403)
        dev2ror1Results.responseJson should be(testSettingsNotConfiguredResponse)
        val dev1ror2Results = dev1Ror2ndInstanceSearchManager.search("test1_index")
        dev1ror2Results.responseCode should be(403)
        dev1ror2Results.responseJson should be(testSettingsNotConfiguredResponse)
        val dev2ror2Results = dev2Ror2ndInstanceSearchManager.search("test2_index")
        dev2ror2Results.responseCode should be(403)
        dev2ror2Results.responseJson should be(testSettingsNotConfiguredResponse)

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
        result.responseCode should be(200)
        result.responseJson("status").str should be("OK")
        result.responseJson("message").str should be("ROR Test settings are invalidated")

        Thread.sleep(12000) // wait for old engine shutdown

        val testConfigResponse = ror1WithIndexConfigAdminActionManager.currentRorTestConfig
        testConfigResponse.responseCode should be(200)
        testConfigResponse.responseJson("status").str should be("TEST_SETTINGS_INVALIDATED")
        testConfigResponse.responseJson("message").str should be("ROR Test settings are invalidated")
        testConfigResponse.responseJson("settings").str should be(getResourceContent("/admin_api/readonlyrest_second_update_with_impersonation.yml"))
        testConfigResponse.responseJson("ttl").str should be("30 minutes")

        // after test core invalidation, impersonations requests should be rejected
        val dev1ror1AfterTestEngineAutoDestruction = dev1Ror1stInstanceSearchManager.search("test1_index")
        dev1ror1AfterTestEngineAutoDestruction.responseCode should be(403)
        dev1ror1AfterTestEngineAutoDestruction.responseJson should be(testSettingsNotConfiguredResponse)
        val dev2ror1AfterTestEngineAutoDestruction = dev2Ror1stInstanceSearchManager.search("test2_index")
        dev2ror1AfterTestEngineAutoDestruction.responseCode should be(403)
        dev2ror1AfterTestEngineAutoDestruction.responseJson should be(testSettingsNotConfiguredResponse)
        val dev1ror2AfterTestEngineAutoDestruction = dev1Ror2ndInstanceSearchManager.search("test1_index")
        dev1ror2AfterTestEngineAutoDestruction.responseCode should be(403)
        dev1ror2AfterTestEngineAutoDestruction.responseJson should be(testSettingsNotConfiguredResponse)
        val dev2ror2AfterTestEngineAutoDestruction = dev2Ror2ndInstanceSearchManager.search("test2_index")
        dev2ror2AfterTestEngineAutoDestruction.responseCode should be(403)
        dev2ror2AfterTestEngineAutoDestruction.responseJson should be(testSettingsNotConfiguredResponse)
      }
    }
    "provide a method for reload test config engine" which {
      "is going to reload ROR test core" when {
        "configuration is new and correct" in {
          def forceReload(rorSettingsResource: String) = {
            val result = ror1WithIndexConfigAdminActionManager.updateRorTestConfig(getResourceContent(rorSettingsResource))

            result.responseCode should be(200)
            result.responseJson("status").str should be("OK")
            result.responseJson("message").str should be("updated settings")
          }

          val dev1Ror1stInstanceSearchManager = new SearchManager(
            clients.head.basicAuthClient("admin1", "pass"),
            Map("x-ror-impersonating" -> "dev1")
          )
          val dev2Ror1stInstanceSearchManager = new SearchManager(
            clients.head.basicAuthClient("admin1", "pass"),
            Map("x-ror-impersonating" -> "dev2")
          )
          val dev1Ror2ndInstanceSearchManager = new SearchManager(
            clients.head.basicAuthClient("admin1", "pass"),
            Map("x-ror-impersonating" -> "dev1")
          )
          val dev2Ror2ndInstanceSearchManager = new SearchManager(
            clients.head.basicAuthClient("admin1", "pass"),
            Map("x-ror-impersonating" -> "dev2")
          )

          // before first reload no user can access indices
          val dev1ror1Results = dev1Ror1stInstanceSearchManager.search("test1_index")
          dev1ror1Results.responseCode should be(403)
          val dev2ror1Results = dev2Ror1stInstanceSearchManager.search("test2_index")
          dev2ror1Results.responseCode should be(403)
          val dev1ror2Results = dev1Ror2ndInstanceSearchManager.search("test1_index")
          dev1ror2Results.responseCode should be(403)
          val dev2ror2Results = dev2Ror2ndInstanceSearchManager.search("test2_index")
          dev2ror2Results.responseCode should be(403)

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
          def forceReload(rorSettingsResource: String, ttl: FiniteDuration) = {
            val result = ror1WithIndexConfigAdminActionManager.updateRorTestConfig(
              getResourceContent(rorSettingsResource),
              ttl
            )

            result.responseCode should be(200)
            result.responseJson("status").str should be("OK")
            result.responseJson("message").str should be("updated settings")
          }

          val dev1Ror1stInstanceSearchManager = new SearchManager(
            clients.head.basicAuthClient("admin1", "pass"),
            Map("x-ror-impersonating" -> "dev1")
          )
          val dev2Ror1stInstanceSearchManager = new SearchManager(
            clients.head.basicAuthClient("admin1", "pass"),
            Map("x-ror-impersonating" -> "dev2")
          )
          val dev1Ror2ndInstanceSearchManager = new SearchManager(
            clients.head.basicAuthClient("admin1", "pass"),
            Map("x-ror-impersonating" -> "dev1")
          )
          val dev2Ror2ndInstanceSearchManager = new SearchManager(
            clients.head.basicAuthClient("admin1", "pass"),
            Map("x-ror-impersonating" -> "dev2")
          )

          // before first reload no user can access indices
          val dev1ror1Results = dev1Ror1stInstanceSearchManager.search("test1_index")
          dev1ror1Results.responseCode should be(403)
          dev1ror1Results.responseJson should be(testSettingsNotConfiguredResponse)
          val dev2ror1Results = dev2Ror1stInstanceSearchManager.search("test2_index")
          dev2ror1Results.responseCode should be(403)
          dev2ror1Results.responseJson should be(testSettingsNotConfiguredResponse)
          val dev1ror2Results = dev1Ror2ndInstanceSearchManager.search("test1_index")
          dev1ror2Results.responseCode should be(403)
          dev1ror2Results.responseJson should be(testSettingsNotConfiguredResponse)
          val dev2ror2Results = dev2Ror2ndInstanceSearchManager.search("test2_index")
          dev2ror2Results.responseJson should be(testSettingsNotConfiguredResponse)

          // first reload
          forceReload(
            "/admin_api/readonlyrest_first_update_with_impersonation.yml",
            30 minutes
          )

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
            ttl = 3 seconds
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
          dev1ror1AfterTestEngineAutoDestruction.responseCode should be(403)
          dev1ror1AfterTestEngineAutoDestruction.responseJson should be(testSettingsNotConfiguredResponse)
          val dev2ror1AfterTestEngineAutoDestruction = dev2Ror1stInstanceSearchManager.search("test2_index")
          dev2ror1AfterTestEngineAutoDestruction.responseCode should be(403)
          dev2ror1AfterTestEngineAutoDestruction.responseJson should be(testSettingsNotConfiguredResponse)
          val dev1ror2AfterTestEngineAutoDestruction = dev1Ror2ndInstanceSearchManager.search("test1_index")
          dev1ror2AfterTestEngineAutoDestruction.responseCode should be(403)
          dev1ror2AfterTestEngineAutoDestruction.responseJson should be(testSettingsNotConfiguredResponse)
          val dev2ror2AfterTestEngineAutoDestruction = dev2Ror2ndInstanceSearchManager.search("test2_index")
          dev2ror2AfterTestEngineAutoDestruction.responseCode should be(403)
          dev2ror2AfterTestEngineAutoDestruction.responseJson should be(testSettingsNotConfiguredResponse)
        }
        "configuration is up to date and new ttl is passed" in {
          val testConfig = getResourceContent("/admin_api/readonlyrest_index.yml")
          val result = ror1WithIndexConfigAdminActionManager
            .updateRorTestConfig(testConfig, FiniteDuration.apply(30, TimeUnit.MINUTES))
            .force()

          result.responseCode should be(200)
          result.responseJson("status").str should be("OK")
          result.responseJson("message").str should be("updated settings")

          val testConfigResponse = ror1WithIndexConfigAdminActionManager.currentRorTestConfig
          testConfigResponse.responseCode should be(200)
          testConfigResponse.responseJson("status").str should be("TEST_SETTINGS_PRESENT")
          testConfigResponse.responseJson("settings").str should be(testConfig)
          testConfigResponse.responseJson("ttl").str should be("30 minutes")
          isIsoDateTime(testConfigResponse.responseJson("valid_to").str) should be(true)
          testConfigResponse.responseJson("warnings").arr should be(empty)

          // wait for valid_to comparison purpose
          Thread.sleep(1000)

          val resultAfterReload = ror1WithIndexConfigAdminActionManager
            .updateRorTestConfig(testConfig, FiniteDuration.apply(45, TimeUnit.MINUTES))
            .force()

          resultAfterReload.responseCode should be(200)
          resultAfterReload.responseJson("status").str should be("OK")
          resultAfterReload.responseJson("message").str should be("updated settings")

          val testConfigResponseAfterReload = ror1WithIndexConfigAdminActionManager.currentRorTestConfig
          testConfigResponseAfterReload.responseCode should be(200)
          testConfigResponseAfterReload.responseJson("status").str should be("TEST_SETTINGS_PRESENT")
          testConfigResponseAfterReload.responseJson("settings").str should be(testConfig)
          testConfigResponseAfterReload.responseJson("ttl").str should be("45 minutes")
          isIsoDateTime(testConfigResponseAfterReload.responseJson("valid_to").str) should be(true)
          testConfigResponseAfterReload.responseJson("warnings").arr should be(empty)

          Instant
            .parse(testConfigResponseAfterReload.responseJson("valid_to").str)
            .isAfter(Instant.parse(testConfigResponse.responseJson("valid_to").str)) should be(true)
        }
      }
      "return info that request is malformed" when {
        "ttl missing" in {
          val config = getResourceContent("/admin_api/readonlyrest_index.yml")
          val requestBody = s"""{"settings": "${escapeJava(config)}"}"""
          val result = ror1WithIndexConfigAdminActionManager
            .updateRorTestConfigRaw(rawRequestBody = requestBody)

          result.responseCode should be(400)
          result.responseJson("status").str should be("FAILED")
          result.responseJson("message").str should be("JSON body malformed: [Could not parse at .ttl: [Attempt to decode value on failed cursor: DownField(ttl)]]")
        }
        "settings key missing" in {
          val requestBody = s"""{"ttl": "30 m"}"""
          val result = ror1WithIndexConfigAdminActionManager
            .updateRorTestConfigRaw(rawRequestBody = requestBody)

          result.responseCode should be(400)
          result.responseJson("status").str should be("FAILED")
          result.responseJson("message").str should be("JSON body malformed: [Could not parse at .settings: [Attempt to decode value on failed cursor: DownField(settings)]]")
        }
        "ttl value in invalid format" in {
          val config = getResourceContent("/admin_api/readonlyrest_index.yml")
          val requestBody = s"""{"settings": "${escapeJava(config)}", "ttl": "30 units"}"""
          val result = ror1WithIndexConfigAdminActionManager
            .updateRorTestConfigRaw(rawRequestBody = requestBody)

          result.responseCode should be(400)
          result.responseJson("status").str should be("FAILED")
          result.responseJson("message").str should be("JSON body malformed: [Could not parse at .ttl: [Cannot parse '30 units' as duration.: DownField(ttl)]]")
        }
      }
      "return info that config is malformed" when {
        "invalid YAML is provided" in {
          val result = ror1WithIndexConfigAdminActionManager
            .updateRorTestConfig(getResourceContent("/admin_api/readonlyrest_malformed.yml"))

          result.responseCode should be(200)
          result.responseJson("status").str should be("FAILED")
          result.responseJson("message").str should startWith("Settings content is malformed")
        }
      }
      "return info that cannot reload" when {
        "ROR core cannot be reloaded" in {
          val result = ror1WithIndexConfigAdminActionManager
            .updateRorTestConfig(getResourceContent("/admin_api/readonlyrest_with_ldap.yml"))

          result.responseCode should be(200)
          result.responseJson("status").str should be("FAILED")
          result.responseJson("message").str should be("Cannot reload new settings: Errors:\nThere was a problem with LDAP connection to: ldap://localhost:389")
        }
      }
    }
    "provide a method for test config engine invalidation" which {
      "will destruct the engine on demand" in {
        def forceReload(rorSettingsResource: String) = {
          val result = ror1WithIndexConfigAdminActionManager.updateRorTestConfig(getResourceContent(rorSettingsResource))

          result.responseCode should be(200)
          result.responseJson("status").str should be("OK")
          result.responseJson("message").str should be("updated settings")
        }

        val dev1Ror1stInstanceSearchManager = new SearchManager(
          clients.head.basicAuthClient("admin1", "pass"),
          Map("x-ror-impersonating" -> "dev1")
        )
        val dev2Ror1stInstanceSearchManager = new SearchManager(
          clients.head.basicAuthClient("admin1", "pass"),
          Map("x-ror-impersonating" -> "dev2")
        )
        val dev1Ror2ndInstanceSearchManager = new SearchManager(
          clients.head.basicAuthClient("admin1", "pass"),
          Map("x-ror-impersonating" -> "dev1")
        )
        val dev2Ror2ndInstanceSearchManager = new SearchManager(
          clients.head.basicAuthClient("admin1", "pass"),
          Map("x-ror-impersonating" -> "dev2")
        )

        // before first reload no user can access indices
        val dev1ror1Results = dev1Ror1stInstanceSearchManager.search("test1_index")
        dev1ror1Results.responseCode should be(403)
        dev1ror1Results.responseJson should be(testSettingsNotConfiguredResponse)
        val dev2ror1Results = dev2Ror1stInstanceSearchManager.search("test2_index")
        dev2ror1Results.responseCode should be(403)
        dev2ror1Results.responseJson should be(testSettingsNotConfiguredResponse)
        val dev1ror2Results = dev1Ror2ndInstanceSearchManager.search("test1_index")
        dev1ror2Results.responseCode should be(403)
        dev1ror2Results.responseJson should be(testSettingsNotConfiguredResponse)
        val dev2ror2Results = dev2Ror2ndInstanceSearchManager.search("test2_index")
        dev2ror2Results.responseCode should be(403)
        dev2ror2Results.responseJson should be(testSettingsNotConfiguredResponse)

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
        result.responseCode should be(200)
        result.responseJson("status").str should be("OK")
        result.responseJson("message").str should be("ROR Test settings are invalidated")

        // after test core invalidation, impersonations requests should be rejected
        val dev1ror1AfterTestEngineAutoDestruction = dev1Ror1stInstanceSearchManager.search("test1_index")
        dev1ror1AfterTestEngineAutoDestruction.responseCode should be(403)
        dev1ror1AfterTestEngineAutoDestruction.responseJson should be(testSettingsNotConfiguredResponse)
        val dev2ror1AfterTestEngineAutoDestruction = dev2Ror1stInstanceSearchManager.search("test2_index")
        dev2ror1AfterTestEngineAutoDestruction.responseCode should be(403)
        dev2ror1AfterTestEngineAutoDestruction.responseJson should be(testSettingsNotConfiguredResponse)
        val dev1ror2AfterTestEngineAutoDestruction = dev1Ror2ndInstanceSearchManager.search("test1_index")
        dev1ror2AfterTestEngineAutoDestruction.responseCode should be(403)
        dev1ror2AfterTestEngineAutoDestruction.responseJson should be(testSettingsNotConfiguredResponse)
        val dev2ror2AfterTestEngineAutoDestruction = dev2Ror2ndInstanceSearchManager.search("test2_index")
        dev2ror2AfterTestEngineAutoDestruction.responseCode should be(403)
        dev2ror2AfterTestEngineAutoDestruction.responseJson should be(testSettingsNotConfiguredResponse)
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

    val indexManager = new IndexManager(ror2_1Node.rorAdminClient, esVersionUsed)
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

  private def isIsoDateTime(str: String): Boolean = {
    Try(DateTimeFormatter.ISO_DATE_TIME.parse(str)).toOption.isDefined
  }

  private lazy val testSettingsNotConfiguredResponse = ujson.read(
    """
      |{
      |  "error":{
      |    "root_cause":[
      |      {
      |        "type":"forbidden_response",
      |        "reason":"forbidden",
      |        "due_to":"TEST_SETTINGS_NOT_CONFIGURED"
      |      }
      |    ],
      |    "type":"forbidden_response",
      |    "reason":"forbidden",
      |    "due_to":"TEST_SETTINGS_NOT_CONFIGURED"
      |  },
      |  "status":403
      |}
      |""".stripMargin)

}
