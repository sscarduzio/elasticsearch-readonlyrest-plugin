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
import org.scalatest.concurrent.Eventually
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterEach, OptionValues}
import tech.beshu.ror.integration.suites.base.support.{BaseManyEsClustersIntegrationTest, MultipleClientsSupport}
import tech.beshu.ror.integration.utils.ESVersionSupportForAnyWordSpecLike
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsClusterContainer, EsClusterProvider}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, IndexManager, RorApiManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers
import tech.beshu.ror.utils.misc.Resources.getResourceContent
import tech.beshu.ror.utils.misc.StringOps._
import ujson.Value

import scala.concurrent.duration._
import scala.language.postfixOps

trait BaseAdminApiSuite
  extends AnyWordSpec
    with BaseManyEsClustersIntegrationTest
    with ESVersionSupportForAnyWordSpecLike
    with MultipleClientsSupport
    with Eventually
    with OptionValues
    with BeforeAndAfterEach 
    with CustomScalaTestMatchers {
  this: EsClusterProvider =>

  protected def readonlyrestIndexName: String

  protected def rorWithIndexConfig: EsClusterContainer

  protected def rorWithNoIndexConfig: EsClusterContainer

  private lazy val ror1_1Node = rorWithIndexConfig.nodes.head
  private lazy val ror1_2Node = rorWithIndexConfig.nodes.tail.head
  private lazy val ror2_1Node = rorWithNoIndexConfig.nodes.head

  private lazy val ror1WithIndexConfigAdminActionManager = new RorApiManager(clients.head.adminClient, esVersionUsed)
  private lazy val rorWithNoIndexConfigAdminActionManager = new RorApiManager(clients.last.adminClient, esVersionUsed)

  private lazy val adminSearchManager = new SearchManager(clients.head.basicAuthClient("admin", "container"), esVersionUsed)
  private lazy val adminIndexManager = new IndexManager(clients.head.basicAuthClient("admin", "container"), esVersionUsed)
  private val testConfigEsDocumentId = "2"
  protected val settingsReloadInterval: FiniteDuration = 2 seconds

  override lazy val esTargets = NonEmptyList.of(ror1_1Node, ror1_2Node, ror2_1Node)
  override lazy val clusterContainers = NonEmptyList.of(rorWithIndexConfig, rorWithNoIndexConfig)

  "An admin REST API" should {
    "provide a method for force refresh ROR config" which {
      "is going to reload ROR core" when {
        "in-index config is newer than current one" in {
          rorWithNoIndexConfigAdminActionManager
            .insertInIndexConfigDirectlyToRorIndex(
              rorConfigIndex = readonlyrestIndexName,
              config = getResourceContent("/admin_api/readonlyrest_index.yml")
            )
            .force()

          val result = rorWithNoIndexConfigAdminActionManager.reloadRorConfig()
          result should have statusCode 200
          result.responseJson should be(ujson.read(
            """
              |{
              |  "status": "ok",
              |  "message": "ReadonlyREST settings were reloaded with success!"
              |}
              |""".stripMargin
          ))
        }
      }
      "return info that config is up to date" when {
        "in-index config is the same as current one" in {
          rorWithNoIndexConfigAdminActionManager
            .insertInIndexConfigDirectlyToRorIndex(
              rorConfigIndex = readonlyrestIndexName,
              config = getResourceContent("/admin_api/readonlyrest.yml")
            )
            .force()

          val result = rorWithNoIndexConfigAdminActionManager.reloadRorConfig()
          result should have statusCode 200
          result.responseJson should be(ujson.read(
            """
              |{
              |  "status": "ko",
              |  "message": "Current settings are already loaded"
              |}
              |""".stripMargin
          ))
        }
      }
      "return info that in-index config does not exist" when {
        "there is no in-index settings configured yet" in {
          val result = rorWithNoIndexConfigAdminActionManager.reloadRorConfig()
          result should have statusCode 200
          result.responseJson should be(ujson.read(
            s"""
               |{
               |  "status": "ko",
               |  "message": "Cannot find settings index"
               |}
               |""".stripMargin
          ))
        }
      }
      "return info that cannot reload config" when {
        "config cannot be reloaded (eg. because LDAP is not achievable)" in {
          rorWithNoIndexConfigAdminActionManager
            .insertInIndexConfigDirectlyToRorIndex(
              rorConfigIndex = readonlyrestIndexName,
              config = getResourceContent("/admin_api/readonlyrest_with_ldap.yml")
            )
            .force()

          val result = rorWithNoIndexConfigAdminActionManager.reloadRorConfig()
          result should have statusCode 200
          result.responseJson should be(ujson.read(
            """
              |{
              |  "status": "ko",
              |  "message": "Cannot reload new settings: Errors:\nThere was a problem with 'ldap1' LDAP connection to: ldap://localhost:389"
              |}
              |""".stripMargin
          ))
        }
      }
    }
    "provide a method for update in-index config" which {
      "is going to reload ROR core and store new in-index config" when {
        "configuration is new and correct" in {
          def forceReload(rorSettingsResource: String) = {
            val result = ror1WithIndexConfigAdminActionManager.updateRorInIndexConfig(getResourceContent(rorSettingsResource))
            result should have statusCode 200
            result.responseJson should be(ujson.read(
              """
                |{
                |  "status": "ok",
                |  "message": "updated settings"
                |}
                |""".stripMargin
            ))

            assertSettingsInIndex(getResourceContent(rorSettingsResource))
          }

          val dev1Ror1stInstanceSearchManager = new SearchManager(clients.head.basicAuthClient("dev1", "test"), esVersionUsed)
          val dev2Ror1stInstanceSearchManager = new SearchManager(clients.head.basicAuthClient("dev2", "test"), esVersionUsed)
          val dev1Ror2ndInstanceSearchManager = new SearchManager(clients.tail.head.basicAuthClient("dev1", "test"), esVersionUsed)
          val dev2Ror2ndInstanceSearchManager = new SearchManager(clients.tail.head.basicAuthClient("dev2", "test"), esVersionUsed)

          // before first reload no user can access indices
          val dev1ror1Results = dev1Ror1stInstanceSearchManager.search("test1_index")
          dev1ror1Results should have statusCode 403
          val dev2ror1Results = dev2Ror1stInstanceSearchManager.search("test2_index")
          dev2ror1Results should have statusCode 403
          val dev1ror2Results = dev1Ror2ndInstanceSearchManager.search("test1_index")
          dev1ror2Results should have statusCode 403
          val dev2ror2Results = dev2Ror2ndInstanceSearchManager.search("test2_index")
          dev2ror2Results should have statusCode 403

          // first reload
          forceReload("/admin_api/readonlyrest_first_update.yml")

          // after first reload only dev1 can access indices
          Thread.sleep(settingsReloadInterval.plus(5 second).toMillis) // have to wait for ROR1_2 instance config reload
          val dev1ror1After1stReloadResults = dev1Ror1stInstanceSearchManager.search("test1_index")
          dev1ror1After1stReloadResults should have statusCode 200
          val dev2ror1After1stReloadResults = dev2Ror1stInstanceSearchManager.search("test2_index")
          dev2ror1After1stReloadResults should have statusCode 403
          val dev1ror2After1stReloadResults = dev1Ror2ndInstanceSearchManager.search("test1_index")
          dev1ror2After1stReloadResults should have statusCode 200
          val dev2ror2After1stReloadResults = dev2Ror2ndInstanceSearchManager.search("test2_index")
          dev2ror2After1stReloadResults should have statusCode 403

          // second reload
          forceReload("/admin_api/readonlyrest_second_update.yml")

          // after second reload dev1 & dev2 can access indices
          Thread.sleep(settingsReloadInterval.plus(5 second).toMillis) // have to wait for ROR1_2 instance config reload
          val dev1ror1After2ndReloadResults = dev1Ror1stInstanceSearchManager.search("test1_index")
          dev1ror1After2ndReloadResults should have statusCode 200
          val dev2ror1After2ndReloadResults = dev2Ror1stInstanceSearchManager.search("test2_index")
          dev2ror1After2ndReloadResults should have statusCode 200
          val dev1ror2After2ndReloadResults = dev1Ror2ndInstanceSearchManager.search("test1_index")
          dev1ror2After2ndReloadResults should have statusCode 200
          val dev2ror2After2ndReloadResults = dev2Ror2ndInstanceSearchManager.search("test2_index")
          dev2ror2After2ndReloadResults should have statusCode 200

        }
      }
      "return info that config is up to date" when {
        "in-index config is the same as provided one" in {
          val result = ror1WithIndexConfigAdminActionManager
            .updateRorInIndexConfig(getResourceContent("/admin_api/readonlyrest_index.yml"))
          assertSettingsInIndex(getResourceContent("/admin_api/readonlyrest_index.yml"))

          result should have statusCode 200
          result.responseJson should be(ujson.read(
            """
              |{
              |  "status": "ko",
              |  "message": "Current settings are already loaded"
              |}
              |""".stripMargin
          ))
        }
      }
      "return info that config is malformed" when {
        "invalid YAML is provided" in {
          val result = ror1WithIndexConfigAdminActionManager
            .updateRorInIndexConfig(getResourceContent("/admin_api/readonlyrest_malformed.yml"))

          result should have statusCode 200
          result.responseJson("status").str should be("ko")
          result.responseJson("message").str should startWith("Settings content is malformed")
        }
      }
      "return info that request is malformed" when {
        "settings key missing" in {
          val result = ror1WithIndexConfigAdminActionManager
            .updateRorInIndexConfigRaw(rawRequestBody = "{}")

          result should have statusCode 400
          result.responseJson should be(ujson.read(
            """
              |{
              |  "status": "ko",
              |  "message": "JSON body malformed: [Could not parse at .settings: [DecodingFailure at .settings: Missing required field]]"
              |}
              |""".stripMargin
          ))
        }
      }
      "return info that cannot reload" when {
        "ROR core cannot be reloaded" in {
          val result = ror1WithIndexConfigAdminActionManager
            .updateRorInIndexConfig(getResourceContent("/admin_api/readonlyrest_with_ldap.yml"))

          result should have statusCode 200
          result.responseJson should be(ujson.read(
            """
              |{
              |  "status": "ko",
              |  "message": "Cannot reload new settings: Errors:\nThere was a problem with 'ldap1' LDAP connection to: ldap://localhost:389"
              |}
              |""".stripMargin
          ))
        }
      }
    }
    "provide a method for fetching current in-index config" which {
      "return current config" when {
        "there is one in index" in {
          val result = ror1WithIndexConfigAdminActionManager
            .updateRorInIndexConfig(getResourceContent("/admin_api/readonlyrest_first_update.yml"))

          result should have statusCode 200
          result.responseJson should be(ujson.read(
            """
              |{
              |  "status": "ok",
              |  "message": "updated settings"
              |}
              |""".stripMargin
          ))

          assertSettingsInIndex(getResourceContent("/admin_api/readonlyrest_first_update.yml"))

          val getIndexConfigResult = ror1WithIndexConfigAdminActionManager.getRorInIndexConfig
          result should have statusCode 200
          getIndexConfigResult.responseJson("status").str should be("ok")
          getIndexConfigResult.responseJson("message").str should be {
            getResourceContent("/admin_api/readonlyrest_first_update.yml")
          }
        }
      }
      "return info that there is no in-index config" when {
        "there is no index" in {
          assertNoRorConfigInIndex(rorWithNoIndexConfigAdminActionManager)
        }
        "there is no config document in index" in {
          val result = ror1WithIndexConfigAdminActionManager
            .updateRorInIndexConfig(getResourceContent("/admin_api/readonlyrest_first_update.yml"))

          result should have statusCode 200
          result.responseJson should be(ujson.read(
            """
              |{
              |  "status": "ok",
              |  "message": "updated settings"
              |}
              |""".stripMargin
          ))

          val adminDocumentManager = new DocumentManager(clients.head.basicAuthClient("admin", "container"), esVersionUsed)
          val matchAllQuery = ujson.read("""{"query" : {"match_all" : {}}}""".stripMargin)
          val deleteResponse = adminDocumentManager.deleteByQuery(readonlyrestIndexName, matchAllQuery)
          deleteResponse should have statusCode 200

          assertNoRorConfigInIndex(rorWithNoIndexConfigAdminActionManager)
        }
      }
    }
    "provide a method for fetching current file config" which {
      "return current config" in {
        val result = ror1WithIndexConfigAdminActionManager.getRorFileConfig
        result should have statusCode 200
        result.responseJson("status").str should be("ok")
        result.responseJson("message").str should be(getResourceContent("/admin_api/readonlyrest.yml"))
      }
    }
    "provide a method for get current test settings" which {
      "return info that test settings are not configured" when {
        "get current test settings" in {
          rorClients.foreach {
            assertTestSettingsNotConfigured
          }
        }
        "get local users" in {
          rorClients.foreach { rorApiManager =>
            val response = rorApiManager.currentRorLocalUsers
            response should have statusCode 200
            response.responseJson should be(ujson.read(
              """
                |{
                |  "status": "TEST_SETTINGS_NOT_CONFIGURED",
                |  "message": "ROR Test settings are not configured"
                |}
                |""".stripMargin
            ))
          }
        }
      }
      "return current test settings" when {
        "should invalidate configuration when index removed" in {
          def forceReload(rorSettingsResource: String): Unit = {
            val testConfig = getResourceContent(rorSettingsResource)
            val configTtl = 30.minutes
            updateRorTestConfig(rorClients.head, testConfig, configTtl)

            // check if config is present in index
            assertTestSettingsInIndex(
              expectedConfig = testConfig,
              expectedTtl = 30 minutes
            )

            eventually { // await until all nodes load config
              rorClients.foreach(assertTestSettingsPresent(_, testConfig, "30 minutes"))
            }
          }

          val dev1SearchManagers = testClients.map { client =>
            new SearchManager(
              client.basicAuthClient("admin1", "pass"), esVersionUsed,
              Map("x-ror-impersonating" -> "dev1")
            )
          }

          val dev2SearchManagers = testClients.map { client =>
            new SearchManager(
              client.basicAuthClient("admin1", "pass"), esVersionUsed,
              Map("x-ror-impersonating" -> "dev2")
            )
          }

          // before first reload no user can access indices
          dev1SearchManagers.foreach(testSettingsNotConfigured(_, "test1_index"))
          dev1SearchManagers.foreach(testSettingsNotConfigured(_, "test2_index"))
          dev2SearchManagers.foreach(testSettingsNotConfigured(_, "test1_index"))
          dev2SearchManagers.foreach(testSettingsNotConfigured(_, "test2_index"))

          // reload config
          forceReload("/admin_api/readonlyrest_first_update_with_impersonation.yml")

          // check if impersonation works
          dev1SearchManagers.foreach(allowedSearch(_, "test1_index"))
          dev1SearchManagers.foreach(indexNotFound(_, "test2_index"))
          dev2SearchManagers.foreach(operationNotAllowed(_, "test1_index"))
          dev2SearchManagers.foreach(operationNotAllowed(_, "test2_index"))

          // drop index containing ror config
          val indexManager = new IndexManager(clients.head.basicAuthClient("admin", "container"), esVersionUsed)
          indexManager.removeIndex(readonlyrestIndexName).force()

          eventually { // await until all nodes invalidate the config
            rorClients.foreach {
              assertTestSettingsNotConfigured
            }
          }

          // check if impersonation is not configured
          dev1SearchManagers.foreach(testSettingsNotConfigured(_, "test1_index"))
          dev1SearchManagers.foreach(testSettingsNotConfigured(_, "test2_index"))
          dev2SearchManagers.foreach(testSettingsNotConfigured(_, "test1_index"))
          dev2SearchManagers.foreach(testSettingsNotConfigured(_, "test2_index"))
        }
        "configuration is valid and response without warnings" in {
          def forceReload(rorSettingsResource: String): Unit = {
            val testConfig = getResourceContent(rorSettingsResource)
            updateRorTestConfig(rorClients.head, testConfig, 30 minutes)

            assertTestSettingsInIndex(testConfig, 30 minutes)

            eventually { // await until all nodes load config
              rorClients.foreach { rorApiManager =>
                assertTestSettingsPresent(rorApiManager, testConfig, "30 minutes")
              }
            }
          }

          val dev1SearchManagers = testClients.map { client =>
            new SearchManager(
              client.basicAuthClient("admin1", "pass"), esVersionUsed,
              Map("x-ror-impersonating" -> "dev1")
            )
          }

          val dev2SearchManagers = testClients.map { client =>
            new SearchManager(
              client.basicAuthClient("admin1", "pass"), esVersionUsed,
              Map("x-ror-impersonating" -> "dev2")
            )
          }

          // before first reload no user can access indices
          dev1SearchManagers.foreach(testSettingsNotConfigured(_, "test1_index"))
          dev1SearchManagers.foreach(testSettingsNotConfigured(_, "test2_index"))
          dev2SearchManagers.foreach(testSettingsNotConfigured(_, "test1_index"))
          dev2SearchManagers.foreach(testSettingsNotConfigured(_, "test2_index"))

          // first reload
          forceReload("/admin_api/readonlyrest_first_update_with_impersonation.yml")

          // after first reload only dev1 can access indices
          dev1SearchManagers.foreach(allowedSearch(_, "test1_index"))
          dev1SearchManagers.foreach(indexNotFound(_, "test2_index"))
          dev2SearchManagers.foreach(operationNotAllowed(_, "test1_index"))
          dev2SearchManagers.foreach(operationNotAllowed(_, "test2_index"))

          // second reload
          forceReload("/admin_api/readonlyrest_second_update_with_impersonation.yml")

          // after second reload dev1 & dev2 can access indices
          dev1SearchManagers.foreach(allowedSearch(_, "test1_index"))
          dev1SearchManagers.foreach(indexNotFound(_, "test2_index"))
          dev2SearchManagers.foreach(indexNotFound(_, "test1_index"))
          dev2SearchManagers.foreach(allowedSearch(_, "test2_index"))
        }
        "configuration is valid and response with warnings" in {
          val warningsJson = ujson.read(
            """
              |[
              |  {
              |    "block_name": "test1",
              |    "rule_name": "auth_key_sha256",
              |    "message": "The rule contains fully hashed username and password. It doesn't support impersonation in this configuration",
              |    "hint": "You can use second version of the rule and use not hashed username. Like that: `auth_key_sha256: USER_NAME:hash(PASSWORD)"
              |  }
              |]
              |""".stripMargin
          )

          def forceReload(rorSettingsResource: String, warnings: ujson.Value): Unit = {
            val testConfig = getResourceContent(rorSettingsResource)
            updateRorTestConfig(rorClients.head, testConfig, 30 minutes, warnings)

            // check if config is present in index
            assertTestSettingsInIndex(
              expectedConfig = testConfig,
              expectedTtl = 30 minutes
            )

            eventually { // await until all nodes load config
              rorClients.foreach { rorApiManager =>
                val response = rorApiManager.currentRorTestConfig
                response should have statusCode 200
                response.responseJson("status").str should be("TEST_SETTINGS_PRESENT")
              }
            }
          }

          val dev1SearchManagers = testClients.map { client =>
            new SearchManager(
              client.basicAuthClient("admin1", "pass"), esVersionUsed,
              Map("x-ror-impersonating" -> "dev1")
            )
          }

          // before reload no user can access indices
          dev1SearchManagers.foreach(testSettingsNotConfigured(_, "test1_index"))

          val rorSettingsResource = "/admin_api/readonlyrest_with_warnings.yml"
          forceReload(rorSettingsResource, warningsJson)

          val testConfig = getResourceContent(rorSettingsResource)
          rorClients.foreach { rorApiManager =>
            assertTestSettingsPresent(
              rorApiManager,
              testConfig,
              "30 minutes",
              warningsJson
            )
          }

          // user with hashed credential cannot be impersonated
          dev1SearchManagers.foreach {
            impersonationNotAllowed(_, "test1_index")
          }
        }
        "return local users" in {
          val testConfig = getResourceContent("/admin_api/readonlyrest_first_update_with_impersonation.yml")
          updateRorTestConfig(rorClients.head, testConfig, 30 minutes)

          assertTestSettingsInIndex(expectedConfig = testConfig, expectedTtl = 30 minutes)

          eventually { // await until all nodes load config
            rorClients.foreach {
              assertTestSettingsPresent(_, testConfig, "30 minutes")
            }
          }

          rorClients.foreach { rorApiManager =>
            val response = rorApiManager.currentRorLocalUsers
            response should have statusCode 200
            response.responseJson("status").str should be("OK")
            (response.responseJson("unknown_users").bool, response.responseJson("users").arr.toList.map(_.str)) should
              be(false, List("dev1")) // admin is filtered out
          }
        }
      }
      "return info that configuration was invalidated" in {
        def forceReload(rorSettingsResource: String): Unit = {
          val testConfig = getResourceContent(rorSettingsResource)
          updateRorTestConfig(rorClients.head, testConfig, 30 minutes)

          assertTestSettingsInIndex(expectedConfig = testConfig, expectedTtl = 30 minutes)

          eventually { // await until all nodes load config
            rorClients.foreach { rorApiManager =>
              assertTestSettingsPresent(rorApiManager, testConfig, "30 minutes")
            }
          }
        }

        val dev1SearchManagers = testClients.map { client =>
          new SearchManager(
            client.basicAuthClient("admin1", "pass"), esVersionUsed,
            Map("x-ror-impersonating" -> "dev1")
          )
        }

        val dev2SearchManagers = testClients.map { client =>
          new SearchManager(
            client.basicAuthClient("admin1", "pass"), esVersionUsed,
            Map("x-ror-impersonating" -> "dev2")
          )
        }

        // before first reload no user can access indices
        dev1SearchManagers.foreach(testSettingsNotConfigured(_, "test1_index"))
        dev1SearchManagers.foreach(testSettingsNotConfigured(_, "test2_index"))
        dev2SearchManagers.foreach(testSettingsNotConfigured(_, "test1_index"))
        dev2SearchManagers.foreach(testSettingsNotConfigured(_, "test2_index"))

        // first reload
        forceReload("/admin_api/readonlyrest_first_update_with_impersonation.yml")

        // after first reload only dev1 can access indices
        dev1SearchManagers.foreach(allowedSearch(_, "test1_index"))
        dev1SearchManagers.foreach(indexNotFound(_, "test2_index"))
        dev2SearchManagers.foreach(operationNotAllowed(_, "test1_index"))
        dev2SearchManagers.foreach(operationNotAllowed(_, "test2_index"))

        // second reload
        val rorSettingsResource = "/admin_api/readonlyrest_second_update_with_impersonation.yml"
        forceReload(rorSettingsResource)

        // after second reload dev1 & dev2 can access indices
        dev1SearchManagers.foreach(allowedSearch(_, "test1_index"))
        dev1SearchManagers.foreach(indexNotFound(_, "test2_index"))
        dev2SearchManagers.foreach(indexNotFound(_, "test1_index"))
        dev2SearchManagers.foreach(allowedSearch(_, "test2_index"))

        invalidateRorTestConfig(rorClients.head)

        eventually {
          rorClients.foreach { rorApiManager =>
            val response = rorApiManager.currentRorTestConfig
            response should have statusCode 200
            response.responseJson("status").str should be("TEST_SETTINGS_INVALIDATED")
            response.responseJson("message").str should be("ROR Test settings are invalidated")
            response.responseJson("settings").str should be(getResourceContent(rorSettingsResource))
            response.responseJson("ttl").str should be("30 minutes")
          }
        }

        // after test core invalidation, impersonations requests should be rejected
        dev1SearchManagers.foreach(testSettingsNotConfigured(_, "test1_index"))
        dev1SearchManagers.foreach(testSettingsNotConfigured(_, "test2_index"))
        dev2SearchManagers.foreach(testSettingsNotConfigured(_, "test1_index"))
        dev2SearchManagers.foreach(testSettingsNotConfigured(_, "test2_index"))
      }
    }
    "provide a method for reload test config engine" which {
      "is going to reload ROR test core with TTL" when {
        "configuration is new and correct" in {
          def forceReload(rorSettingsResource: String, configTtl: FiniteDuration, configTtlString: String): Unit = {
            val testConfig = getResourceContent(rorSettingsResource)
            updateRorTestConfig(rorClients.head, testConfig, configTtl)

            assertTestSettingsInIndex(expectedConfig = testConfig, expectedTtl = configTtl)

            eventually { // await until all nodes load config
              rorClients.foreach { rorApiManager =>
                assertTestSettingsPresent(rorApiManager, testConfig, configTtlString)
              }
            }
          }

          eventually { // await until all nodes load config
            rorClients.foreach { rorApiManager =>
              assertTestSettingsNotConfigured(rorApiManager)
            }
          }

          val dev1SearchManagers = testClients.map { client =>
            new SearchManager(
              client.basicAuthClient("admin1", "pass"), esVersionUsed,
              Map("x-ror-impersonating" -> "dev1")
            )
          }

          val dev2SearchManagers = testClients.map { client =>
            new SearchManager(
              client.basicAuthClient("admin1", "pass"), esVersionUsed,
              Map("x-ror-impersonating" -> "dev2")
            )
          }

          // before first reload no user can access indices
          dev1SearchManagers.foreach(testSettingsNotConfigured(_, "test1_index"))
          dev1SearchManagers.foreach(testSettingsNotConfigured(_, "test2_index"))
          dev2SearchManagers.foreach(testSettingsNotConfigured(_, "test1_index"))
          dev2SearchManagers.foreach(testSettingsNotConfigured(_, "test2_index"))

          // first reload
          forceReload(
            rorSettingsResource = "/admin_api/readonlyrest_first_update_with_impersonation.yml",
            configTtl = 30 minutes,
            configTtlString = "30 minutes"
          )

          eventually { // await until all nodes load config
            rorClients.foreach { rorApiManager =>
              assertTestSettingsPresent(
                rorApiManager = rorApiManager,
                testConfig = getResourceContent("/admin_api/readonlyrest_first_update_with_impersonation.yml"),
                expectedTtl = "30 minutes"
              )
            }
          }

          // after first reload only dev1 can access indices
          dev1SearchManagers.foreach(allowedSearch(_, "test1_index"))
          dev1SearchManagers.foreach(indexNotFound(_, "test2_index"))
          dev2SearchManagers.foreach(operationNotAllowed(_, "test1_index"))
          dev2SearchManagers.foreach(operationNotAllowed(_, "test2_index"))

          // second reload
          val rorSettingsResource = "/admin_api/readonlyrest_second_update_with_impersonation.yml"
          val configTtl = 5.seconds
          forceReload(
            rorSettingsResource = rorSettingsResource,
            configTtl = configTtl,
            configTtlString = "5 seconds"
          )

          eventually { // await until all nodes load config
            rorClients.foreach { rorApiManager =>
              assertTestSettingsPresent(
                rorApiManager = rorApiManager,
                testConfig = getResourceContent(rorSettingsResource),
                expectedTtl = "5 seconds"
              )
            }
          }

          // after second reload dev1 & dev2 can access indices
          dev1SearchManagers.foreach(allowedSearch(_, "test1_index"))
          dev1SearchManagers.foreach(indexNotFound(_, "test2_index"))
          dev2SearchManagers.foreach(indexNotFound(_, "test1_index"))
          dev2SearchManagers.foreach(allowedSearch(_, "test2_index"))

          // wait for test engine auto-destruction
          Thread.sleep(configTtl.toMillis)

          rorClients.foreach { rorApiManager =>
            assertTestSettingsInvalidated(rorApiManager, getResourceContent(rorSettingsResource), "5 seconds")
          }

          dev1SearchManagers.foreach(testSettingsNotConfigured(_, "test1_index"))
          dev1SearchManagers.foreach(testSettingsNotConfigured(_, "test2_index"))
          dev2SearchManagers.foreach(testSettingsNotConfigured(_, "test1_index"))
          dev2SearchManagers.foreach(testSettingsNotConfigured(_, "test2_index"))
        }
        "configuration is up to date and new ttl is passed" in {
          val testConfig = getResourceContent("/admin_api/readonlyrest_index.yml")
          updateRorTestConfig(rorClients.head, testConfig, 30 minutes)
          assertTestSettingsInIndex(expectedConfig = testConfig, expectedTtl = 30 minutes)

          eventually { // await until all nodes load config
            rorClients.foreach {
              assertTestSettingsPresent(_, testConfig, "30 minutes")
            }
          }

          val timestamps =
            rorClients
              .map(_.currentRorTestConfig.responseJson("valid_to").str)
              .map(Instant.parse).toSet

          timestamps.size should be(1)

          // wait for valid_to comparison purpose
          Thread.sleep(100)

          updateRorTestConfig(rorClients.head, testConfig, 45 minutes)
          assertTestSettingsInIndex(expectedConfig = testConfig, expectedTtl = 45 minutes)

          eventually { // await until all nodes load config
            rorClients.foreach {
              assertTestSettingsPresent(_, testConfig, "45 minutes")
            }
          }

          val timestampsAfterReload =
            rorClients
              .map(_.currentRorTestConfig.responseJson("valid_to").str)
              .map(Instant.parse)
              .toSet
          timestampsAfterReload.size should be(1)

          timestampsAfterReload.head.isAfter(timestamps.head) should be(true)
        }
      }
      "return info that request is malformed" when {
        "ttl missing" in {
          val config = getResourceContent("/admin_api/readonlyrest_index.yml")
          val requestBody = s"""{"settings": "${escapeJava(config)}"}"""

          rorClients.foreach { rorApiManager =>
            val result = rorApiManager
              .updateRorTestConfigRaw(rawRequestBody = requestBody)

            result should have statusCode 400
            result.responseJson should be(ujson.read(
              """
                |{
                |  "status": "FAILED",
                |  "message": "JSON body malformed: [Could not parse at .ttl: [DecodingFailure at .ttl: Missing required field]]"
                |}
                |""".stripMargin
            ))
          }
        }
        "settings key missing" in {
          val requestBody = s"""{"ttl": "30 m"}"""
          rorClients.foreach { rorApiManager =>
            val result = rorApiManager
              .updateRorTestConfigRaw(rawRequestBody = requestBody)

            result should have statusCode 400
            result.responseJson should be(ujson.read(
              """
                |{
                |  "status": "FAILED",
                |  "message": "JSON body malformed: [Could not parse at .settings: [DecodingFailure at .settings: Missing required field]]"
                |}
                |""".stripMargin
            ))
          }
        }
        "ttl value in invalid format" in {
          val config = getResourceContent("/admin_api/readonlyrest_index.yml")
          val requestBody = s"""{"settings": "${escapeJava(config)}", "ttl": "30 units"}"""

          rorClients.foreach { rorApiManager =>
            val result = rorApiManager
              .updateRorTestConfigRaw(rawRequestBody = requestBody)

            result should have statusCode 400
            result.responseJson should be(ujson.read(
              """
                |{
                |  "status": "FAILED",
                |  "message": "JSON body malformed: [Could not parse at .ttl: [DecodingFailure at .ttl: Cannot parse '30 units' as duration.]]"
                |}
                |""".stripMargin
            ))
          }
        }
      }
      "return info that config is malformed" when {
        "invalid YAML is provided" in {
          rorClients.foreach { rorApiManager =>
            val result = rorApiManager
              .updateRorTestConfig(getResourceContent("/admin_api/readonlyrest_malformed.yml"))

            result should have statusCode 200
            result.responseJson("status").str should be("FAILED")
            result.responseJson("message").str should startWith("Settings content is malformed")
          }
        }
      }
      "return info that cannot reload" when {
        "ROR core cannot be reloaded" in {
          rorClients.foreach { rorApiManager =>
            val result = rorApiManager
              .updateRorTestConfig(getResourceContent("/admin_api/readonlyrest_with_ldap.yml"))

            result should have statusCode 200
            result.responseJson should be(ujson.read(
              s"""
                 |{
                 |  "status": "FAILED",
                 |  "message": "Cannot reload new settings: Errors:\\nThere was a problem with 'ldap1' LDAP connection to: ldap://localhost:389"
                 |}
                 |""".stripMargin
            ))
          }
        }
      }
    }
    "provide a method for test config engine invalidation" which {
      "will destruct the engine on demand" in {
        def forceReload(rorSettingsResource: String): Unit = {
          val testConfig = getResourceContent(rorSettingsResource)

          updateRorTestConfig(rorClients.head, testConfig, 30 minutes)

          assertTestSettingsInIndex(
            expectedConfig = testConfig,
            expectedTtl = 30 minutes
          )

          eventually { // await until all nodes load config
            rorClients.foreach { rorApiManager =>
              assertTestSettingsPresent(rorApiManager, testConfig, "30 minutes")
            }
          }
        }

        val dev1SearchManagers = testClients.map { client =>
          new SearchManager(
            client.basicAuthClient("admin1", "pass"), esVersionUsed,
            Map("x-ror-impersonating" -> "dev1")
          )
        }

        val dev2SearchManagers = testClients.map { client =>
          new SearchManager(
            client.basicAuthClient("admin1", "pass"), esVersionUsed,
            Map("x-ror-impersonating" -> "dev2")
          )
        }

        dev1SearchManagers.foreach(testSettingsNotConfigured(_, "test1_index"))
        dev1SearchManagers.foreach(testSettingsNotConfigured(_, "test2_index"))
        dev2SearchManagers.foreach(testSettingsNotConfigured(_, "test1_index"))
        dev2SearchManagers.foreach(testSettingsNotConfigured(_, "test2_index"))

        // first reload
        forceReload("/admin_api/readonlyrest_first_update_with_impersonation.yml")

        // after first reload only dev1 can access indices
        dev1SearchManagers.foreach(allowedSearch(_, "test1_index"))
        dev1SearchManagers.foreach(indexNotFound(_, "test2_index"))
        dev2SearchManagers.foreach(operationNotAllowed(_, "test1_index"))
        dev2SearchManagers.foreach(operationNotAllowed(_, "test2_index"))

        // second reload
        forceReload("/admin_api/readonlyrest_second_update_with_impersonation.yml")

        // after second reload dev1 & dev2 can access indices
        dev1SearchManagers.foreach(allowedSearch(_, "test1_index"))
        dev1SearchManagers.foreach(indexNotFound(_, "test2_index"))
        dev2SearchManagers.foreach(indexNotFound(_, "test1_index"))
        dev2SearchManagers.foreach(allowedSearch(_, "test2_index"))

        invalidateRorTestConfig(rorClients.head)

        Thread.sleep(settingsReloadInterval.toMillis) // wait for engines reload

        // wait for engines reload
        rorClients.foreach { rorApiManager =>
          assertTestSettingsInvalidated(
            rorApiManager = rorApiManager,
            testConfig = getResourceContent("/admin_api/readonlyrest_second_update_with_impersonation.yml"),
            expectedTtl = "30 minutes"
          )
        }

        // after test core invalidation, impersonations requests should be rejected
        dev1SearchManagers.foreach(testSettingsNotConfigured(_, "test1_index"))
        dev1SearchManagers.foreach(testSettingsNotConfigured(_, "test2_index"))
        dev2SearchManagers.foreach(testSettingsNotConfigured(_, "test1_index"))
        dev2SearchManagers.foreach(testSettingsNotConfigured(_, "test2_index"))
      }
    }
    "main ROR config and test ROR config coexistence check" when {
      "get main ROR index config" should {
        "return no index config" when {
          "no main and test config in the index" in {
            adminIndexManager.removeIndex(readonlyrestIndexName)
            rorClients.foreach { rorApiManager =>
              assertNoRorConfigInIndex(rorApiManager)
              assertTestSettingsNotConfigured(rorApiManager)
            }
          }
          "only test config in the index" in {
            def forceReloadTestSettings(testConfig: String): Unit = {
              updateRorTestConfig(rorClients.head, testConfig, 30 minutes)
              assertTestSettingsInIndex(
                expectedConfig = testConfig,
                expectedTtl = 30 minutes
              )
            }

            adminIndexManager.removeIndex(readonlyrestIndexName)

            rorClients.foreach { rorApiManager =>
              assertNoRorConfigInIndex(rorApiManager)
              assertTestSettingsNotConfigured(rorApiManager)
            }

            val config = getResourceContent("/admin_api/readonlyrest_first_update_with_impersonation.yml")
            forceReloadTestSettings(config)
            Thread.sleep(settingsReloadInterval.toMillis) // wait for engines reload

            rorClients.foreach { rorApiManager =>
              assertNoRorConfigInIndex(rorApiManager)
              assertTestSettingsPresent(
                rorApiManager,
                testConfig = config,
                expectedTtl = "30 minutes"
              )
            }
          }
        }
        "return index config" when {
          "only main config in the index" in {
            def forceReloadMainSettings(config: String) = {
              updateRorMainConfig(rorClients.head, config)
              assertSettingsInIndex(expectedConfig = config)
            }

            adminIndexManager.removeIndex(readonlyrestIndexName)

            rorClients.foreach { rorApiManager =>
              assertNoRorConfigInIndex(rorApiManager)
              assertTestSettingsNotConfigured(rorApiManager)
            }

            val config = getResourceContent("/admin_api/readonlyrest_first_update_with_impersonation.yml")
            forceReloadMainSettings(config)

            rorClients.foreach { rorApiManager =>
              assertInIndexConfigPresent(rorApiManager, config)
              assertTestSettingsNotConfigured(rorApiManager)
            }
          }
          "main and test configs in the index" in {
            def forceReloadMainSettings(config: String) = {
              updateRorMainConfig(rorClients.head, config)
              assertSettingsInIndex(expectedConfig = config)
            }

            def forceReloadTestSettings(testConfig: String): Unit = {
              updateRorTestConfig(rorClients.head, testConfig, 30 minutes)
              assertTestSettingsInIndex(
                expectedConfig = testConfig,
                expectedTtl = 30 minutes
              )
            }

            adminIndexManager.removeIndex(readonlyrestIndexName)

            rorClients.foreach { rorApiManager =>
              assertNoRorConfigInIndex(rorApiManager)
              assertTestSettingsNotConfigured(rorApiManager)
            }

            val config = getResourceContent("/admin_api/readonlyrest_first_update_with_impersonation.yml")
            forceReloadMainSettings(config)
            forceReloadTestSettings(config)

            Thread.sleep(settingsReloadInterval.toMillis) // wait for engines reload
            rorClients.foreach { client =>
              assertInIndexConfigPresent(client, config = config)
              assertTestSettingsPresent(client, testConfig = config, expectedTtl = "30 minutes")
            }
          }
        }
      }
    }
  }

  override protected def beforeEach(): Unit = {
    // back to configuration loaded on container start
    rorWithNoIndexConfigAdminActionManager
      .updateRorInIndexConfig(getResourceContent("/admin_api/readonlyrest.yml"))
      .force()

    new IndexManager(ror2_1Node.adminClient, esVersionUsed).removeIndex(readonlyrestIndexName)

    adminIndexManager.removeIndex(readonlyrestIndexName)

    ror1WithIndexConfigAdminActionManager
      .updateRorInIndexConfig(getResourceContent("/admin_api/readonlyrest_index.yml"))
      .force()

    eventually { // await until all nodes invalidate the config
      rorClients.foreach(assertTestSettingsNotConfigured)
    }
  }

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = settingsReloadInterval.plus(2 seconds), interval = 1 second)

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

  private def assertSettingsInIndex(expectedConfig: String) = {
    val indexSearchResponse = adminSearchManager.search(readonlyrestIndexName)
    indexSearchResponse should have statusCode 200
    val indexSearchHits = indexSearchResponse.responseJson("hits")("hits").arr.toList
    indexSearchHits.size should be >= 1 // at least main document or test document should be present
    val testSettingsDocumentHit = indexSearchHits.find { searchResult =>
      (searchResult("_index").str, searchResult("_id").str) === (readonlyrestIndexName, "1")
    }.value

    val testSettingsDocumentContent = testSettingsDocumentHit("_source")
    testSettingsDocumentContent("settings").str should be(expectedConfig)
  }

  private def assertTestSettingsInIndex(expectedConfig: String, expectedTtl: FiniteDuration) = {
    val indexSearchResponse = adminSearchManager.search(readonlyrestIndexName)
    indexSearchResponse should have statusCode 200
    val indexSearchHits = indexSearchResponse.responseJson("hits")("hits").arr.toList
    indexSearchHits.size should be >= 1 // at least main document or test document should be present
    val testSettingsDocumentHit = indexSearchHits.find { searchResult =>
      (searchResult("_index").str, searchResult("_id").str) === (readonlyrestIndexName, testConfigEsDocumentId)
    }.value

    val testSettingsDocumentContent = testSettingsDocumentHit("_source")
    testSettingsDocumentContent("settings").str should be(expectedConfig)
    testSettingsDocumentContent("expiration_ttl_millis").str should be(expectedTtl.toMillis.toString)
    testSettingsDocumentContent("expiration_timestamp").str.isInIsoDateTimeFormat should be(true)
    val mocksContent = ujson.read(testSettingsDocumentContent("auth_services_mocks").str)
    mocksContent("ldapMocks").obj.isEmpty should be(true)
    mocksContent("externalAuthenticationMocks").obj.isEmpty should be(true)
    mocksContent("externalAuthorizationMocks").obj.isEmpty should be(true)
  }

  private def assertNoRorConfigInIndex(rorApiManager: RorApiManager) = {
    val result = rorApiManager.getRorInIndexConfig
    result should have statusCode 200
    result.responseJson should be(ujson.read(
      """
        |{
        |  "status": "empty",
        |  "message": "Cannot find settings index"
        |}
        |""".stripMargin
    ))
  }

  private def assertInIndexConfigPresent(rorApiManager: RorApiManager, config: String) = {
    val getIndexConfigResult = rorApiManager.getRorInIndexConfig
    getIndexConfigResult should have statusCode 200
    getIndexConfigResult.responseJson("status").str should be("ok")
    getIndexConfigResult.responseJson("message").str should be(config)
  }

  private def assertTestSettingsNotConfigured(rorApiManager: RorApiManager) = {
    val response = rorApiManager.currentRorTestConfig
    response should have statusCode 200
    response.responseJson should be(ujson.read(
      """
        |{
        |  "status": "TEST_SETTINGS_NOT_CONFIGURED",
        |  "message": "ROR Test settings are not configured"
        |}
        |""".stripMargin
    ))
  }

  private def assertTestSettingsPresent(rorApiManager: RorApiManager,
                                        testConfig: String,
                                        expectedTtl: String,
                                        expectedWarningsJson: Value = ujson.read("[]")) = {
    val response = rorApiManager.currentRorTestConfig
    response should have statusCode 200
    response.responseJson("status").str should be("TEST_SETTINGS_PRESENT")
    response.responseJson("settings").str should be(testConfig)
    response.responseJson("ttl").str should be(expectedTtl)
    response.responseJson("valid_to").str.isInIsoDateTimeFormat should be(true)
    response.responseJson("warnings") should be(expectedWarningsJson)
  }

  private def assertTestSettingsInvalidated(rorApiManager: RorApiManager,
                                            testConfig: String,
                                            expectedTtl: String) = {
    val response = rorApiManager.currentRorTestConfig
    response should have statusCode 200
    response.responseJson("status").str should be("TEST_SETTINGS_INVALIDATED")
    response.responseJson("message").str should be("ROR Test settings are invalidated")
    response.responseJson("settings").str should be(testConfig)
    response.responseJson("ttl").str should be(expectedTtl)
  }

  private def updateRorTestConfig(rorApiManager: RorApiManager,
                                  testConfig: String,
                                  configTtl: FiniteDuration,
                                  expectedWarningsJson: Value = ujson.read("[]")) = {
    val response = rorApiManager.updateRorTestConfig(testConfig, configTtl)
    response should have statusCode 200
    response.responseJson("status").str should be("OK")
    response.responseJson("message").str should be("updated settings")
    response.responseJson("valid_to").str.isInIsoDateTimeFormat should be(true)
    response.responseJson("warnings") should be(expectedWarningsJson)
  }

  private def updateRorMainConfig(rorApiManager: RorApiManager, config: String) = {
    val result = rorApiManager.updateRorInIndexConfig(config)
    result should have statusCode 200
    result.responseJson should be(ujson.read(
      """
        |{
        |  "status": "ok",
        |  "message": "updated settings"
        |}
        |""".stripMargin
    ))
  }

  private def invalidateRorTestConfig(rorApiManager: RorApiManager) = {
    val response = rorApiManager.invalidateRorTestConfig()
    response should have statusCode 200
    response.responseJson should be(ujson.read(
      """
        |{
        |  "status": "OK",
        |  "message": "ROR Test settings are invalidated"
        |}
        |""".stripMargin
    ))
  }

  private def testSettingsNotConfigured(sm: SearchManager, indexName: String) = {
    val results = sm.search(indexName)
    results should have statusCode 403
    results.responseJson should be(ujson.read(
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
        |""".stripMargin
    ))
  }

  private def allowedSearch(sm: SearchManager, indexName: String) = {
    val results = sm.search(indexName)
    results should have statusCode 200
  }

  private def indexNotFound(sm: SearchManager, indexName: String) = {
    val results = sm.search(indexName)
    results should have statusCode 404

    val generatedIndex = results.responseJson("error")("index").str
    generatedIndex should fullyMatch regex s"^${indexName}_ROR_[a-zA-Z0-9]{10}$$".r

    results.responseJson should be(ujson.read(
      s"""
        |{
        |  "error":{
        |    "root_cause":[
        |      {
        |        "type":"index_not_found_exception",
        |        "reason":"no such index [$generatedIndex]",
        |        "resource.type":"index_or_alias",
        |        "resource.id":"$generatedIndex",
        |        "index_uuid":"_na_",
        |        "index":"$generatedIndex"
        |      }
        |    ],
        |    "type":"index_not_found_exception",
        |    "reason":"no such index [$generatedIndex]",
        |    "resource.type":"index_or_alias",
        |    "resource.id":"$generatedIndex",
        |    "index_uuid":"_na_",
        |    "index":"$generatedIndex"
        |  },
        |  "status":404
        |}""".stripMargin
    ))
  }


  private def operationNotAllowed(sm: SearchManager, indexName: String) = {
    val results = sm.search(indexName)
    results should have statusCode 403
    results.responseJson should be(ujson.read(
      """
        |{
        |  "error":{
        |    "root_cause":[
        |      {
        |        "type":"forbidden_response",
        |        "reason":"forbidden",
        |        "due_to":"OPERATION_NOT_ALLOWED"
        |      }
        |    ],
        |  "type":"forbidden_response",
        |  "reason":"forbidden",
        |  "due_to":"OPERATION_NOT_ALLOWED"
        |  },
        |  "status":403
        |}""".stripMargin
    ))
  }

  private def impersonationNotAllowed(sm: SearchManager, indexName: String) = {
    val results = sm.search(indexName)
    results should have statusCode 403
    results.responseJson should be(ujson.read(
      """
        |{
        |  "error":{
        |    "root_cause":[
        |      {
        |        "type":"forbidden_response",
        |        "reason":"forbidden",
        |        "due_to":"IMPERSONATION_NOT_ALLOWED"
        |      }
        |    ],
        |  "type":"forbidden_response",
        |  "reason":"forbidden",
        |  "due_to":"IMPERSONATION_NOT_ALLOWED"
        |  },
        |  "status":403
        |}""".stripMargin
    ))
  }

  private def rorClients = {
    testClients
      .map(_.adminClient)
      .map(new RorApiManager(_, esVersionUsed))
  }

  private def testClients = {
    clients
      .toList
      .dropRight(1)
  }
}
