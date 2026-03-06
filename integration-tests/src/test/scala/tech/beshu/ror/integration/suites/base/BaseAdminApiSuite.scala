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
import org.apache.commons.text.StringEscapeUtils.escapeJava
import org.scalatest.concurrent.Eventually
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterEach, OptionValues}
import tech.beshu.ror.integration.suites.base.support.{BaseManyEsClustersIntegrationTest, MultipleClientsSupport}
import tech.beshu.ror.integration.utils.ESVersionSupportForAnyWordSpecLike
import tech.beshu.ror.utils.TestUjson.ujson
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsClusterContainer, EsClusterProvider}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, IndexManager, RorApiManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.{CustomScalaTestMatchers, Version}
import tech.beshu.ror.utils.misc.Resources.getResourceContent
import ujson.Value
import tech.beshu.ror.utils.misc.ScalaUtils.StringDateTimeOps

import scala.concurrent.duration.*
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

  protected def rorWithIndexSettings: EsClusterContainer

  protected def rorWithNoIndexSettings: EsClusterContainer

  private lazy val ror1_1Node = rorWithIndexSettings.nodes.head
  private lazy val ror1_2Node = rorWithIndexSettings.nodes.tail.head
  private lazy val ror2_1Node = rorWithNoIndexSettings.nodes.head

  private lazy val ror1WithIndexSettingsAdminActionManager = new RorApiManager(clients.head.adminClient, esVersionUsed)
  private lazy val rorWithNoIndexSettingsAdminActionManager = new RorApiManager(clients.last.adminClient, esVersionUsed)

  private lazy val adminSearchManager = new SearchManager(clients.head.basicAuthClient("admin", "container"), esVersionUsed)
  private lazy val adminIndexManager = new IndexManager(clients.head.basicAuthClient("admin", "container"), esVersionUsed)
  private val testSettingsEsDocumentId = "2"
  protected val settingsReloadInterval: FiniteDuration = 5 seconds

  override lazy val esTargets = NonEmptyList.of(ror1_1Node, ror1_2Node, ror2_1Node)
  override lazy val clusterContainers = NonEmptyList.of(rorWithIndexSettings, rorWithNoIndexSettings)

  "An admin REST API" should {
    "provide a method for force refresh ROR settings" which {
      "is going to reload ROR core" when {
        "in-index settings is newer than current one" in {
          rorWithNoIndexSettingsAdminActionManager
            .insertInIndexSettingsDirectlyToRorIndex(
              rorIndex = readonlyrestIndexName,
              settings = getResourceContent("/admin_api/readonlyrest_index.yml")
            )
            .force()

          val result = rorWithNoIndexSettingsAdminActionManager.reloadRorSettings()
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
      "return info that settings are up to date" when {
        "in-index settings are the same as current one" in {
          rorWithNoIndexSettingsAdminActionManager
            .insertInIndexSettingsDirectlyToRorIndex(
              rorIndex = readonlyrestIndexName,
              settings = getResourceContent("/admin_api/readonlyrest.yml")
            )
            .force()

          val result = rorWithNoIndexSettingsAdminActionManager.reloadRorSettings()
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
      "return info that in-index settings do not exist" when {
        "there is no in-index settings configured yet" in {
          val result = rorWithNoIndexSettingsAdminActionManager.reloadRorSettings()
          result should have statusCode 200
          result.responseJson should be(ujson.read(
            s"""
               |{
               |  "status": "ko",
               |  "message": "Cannot find ReadonlyREST settings index"
               |}
               |""".stripMargin
          ))
        }
      }
      "return info that cannot reload settings" when {
        "settings cannot be reloaded (eg. because LDAP is not achievable)" in {
          rorWithNoIndexSettingsAdminActionManager
            .insertInIndexSettingsDirectlyToRorIndex(
              rorIndex = readonlyrestIndexName,
              settings = getResourceContent("/admin_api/readonlyrest_with_ldap.yml")
            )
            .force()

          val result = rorWithNoIndexSettingsAdminActionManager.reloadRorSettings()
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
    "provide a method for update in-index settings" which {
      "is going to reload ROR core and store new in-index settings" when {
        "settings are new and correct" in {
          def forceReload(rorSettingsResource: String) = {
            val result = ror1WithIndexSettingsAdminActionManager.updateRorInIndexSettings(getResourceContent(rorSettingsResource))
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
          Thread.sleep(settingsReloadInterval.plus(10 second).toMillis) // have to wait for ROR1_2 instance settings reload
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
          Thread.sleep(settingsReloadInterval.plus(10 second).toMillis) // have to wait for ROR1_2 instance settings reload
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
      "return info that settings are up to date" when {
        "in-index settings are the same as provided one" in {
          val result = ror1WithIndexSettingsAdminActionManager
            .updateRorInIndexSettings(getResourceContent("/admin_api/readonlyrest_index.yml"))
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
      "return info that settings are malformed" when {
        "invalid YAML is provided" in {
          val result = ror1WithIndexSettingsAdminActionManager
            .updateRorInIndexSettings(getResourceContent("/admin_api/readonlyrest_malformed.yml"))

          result should have statusCode 200
          result.responseJson("status").str should be("ko")
          result.responseJson("message").str should startWith("Settings content is malformed")
        }
      }
      "return info that request is malformed" when {
        "settings key missing" in {
          val result = ror1WithIndexSettingsAdminActionManager
            .updateRorInIndexSettingsRaw(rawRequestBody = "{}")

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
          val result = ror1WithIndexSettingsAdminActionManager
            .updateRorInIndexSettings(getResourceContent("/admin_api/readonlyrest_with_ldap.yml"))

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
    "provide a method for fetching current in-index settings" which {
      "return current settings" when {
        "there is one in index" in {
          val result = ror1WithIndexSettingsAdminActionManager
            .updateRorInIndexSettings(getResourceContent("/admin_api/readonlyrest_first_update.yml"))

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

          val getIndexSettingsResult = ror1WithIndexSettingsAdminActionManager.getRorInIndexSettings
          result should have statusCode 200
          getIndexSettingsResult.responseJson("status").str should be("ok")
          getIndexSettingsResult.responseJson("message").str should be {
            getResourceContent("/admin_api/readonlyrest_first_update.yml")
          }
        }
      }
      "return info that there is no in-index settings" when {
        "there is no index" in {
          assertNoRorSettingsInIndex(rorWithNoIndexSettingsAdminActionManager)
        }
        "there are no settings document in index" in {
          val result = ror1WithIndexSettingsAdminActionManager
            .updateRorInIndexSettings(getResourceContent("/admin_api/readonlyrest_first_update.yml"))

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

          assertNoRorSettingsInIndex(rorWithNoIndexSettingsAdminActionManager)
        }
      }
    }
    "provide a method for fetching current file settings" which {
      "return current settings" in {
        val result = ror1WithIndexSettingsAdminActionManager.getRorFileSettings
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
        "should invalidate settings when index removed" in {
          def forceReload(rorSettingsResource: String): Unit = {
            val testSettings = getResourceContent(rorSettingsResource)
            val settingsTtl = 30.minutes
            updateRorTestSettings(rorClients.head, testSettings, settingsTtl)

            // check if settings are present in index
            assertTestSettingsInIndex(
              expectedSettings = testSettings,
              expectedTtl = settingsTtl
            )

            eventually { // await until all nodes load settings
              rorClients.foreach(assertTestSettingsPresent(_, testSettings, "30 minutes"))
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
          dev1SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test1_index"))
          dev1SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test2_index"))
          dev2SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test1_index"))
          dev2SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test2_index"))

          // reload settings
          forceReload("/admin_api/readonlyrest_first_update_with_impersonation.yml")

          // check if impersonation works
          dev1SearchManagers.foreach(assertAllowedSearch(_, "test1_index"))
          dev1SearchManagers.foreach(assertIndexNotFound(_, "test2_index"))
          dev2SearchManagers.foreach(assertOperationNotAllowed(_, "test1_index"))
          dev2SearchManagers.foreach(assertOperationNotAllowed(_, "test2_index"))

          // drop index containing ror settings
          val indexManager = new IndexManager(clients.head.basicAuthClient("admin", "container"), esVersionUsed)
          indexManager.removeIndex(readonlyrestIndexName).force()

          eventually { // await until all nodes invalidate the settings
            rorClients.foreach {
              assertTestSettingsNotConfigured
            }
          }

          // check if impersonation is not configured
          dev1SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test1_index"))
          dev1SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test2_index"))
          dev2SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test1_index"))
          dev2SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test2_index"))
        }
        "settings are valid and response without warnings" in {
          def forceReload(rorSettingsResource: String): Unit = {
            val testSettingsYaml = getResourceContent(rorSettingsResource)
            updateRorTestSettings(rorClients.head, testSettingsYaml, 30 minutes)

            assertTestSettingsInIndex(testSettingsYaml, 30 minutes)

            eventually { // await until all nodes load settings
              rorClients.foreach { rorApiManager =>
                assertTestSettingsPresent(rorApiManager, testSettingsYaml, "30 minutes")
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
          dev1SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test1_index"))
          dev1SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test2_index"))
          dev2SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test1_index"))
          dev2SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test2_index"))

          // first reload
          forceReload("/admin_api/readonlyrest_first_update_with_impersonation.yml")

          // after first reload only dev1 can access indices
          dev1SearchManagers.foreach(assertAllowedSearch(_, "test1_index"))
          dev1SearchManagers.foreach(assertIndexNotFound(_, "test2_index"))
          dev2SearchManagers.foreach(assertOperationNotAllowed(_, "test1_index"))
          dev2SearchManagers.foreach(assertOperationNotAllowed(_, "test2_index"))

          // second reload
          forceReload("/admin_api/readonlyrest_second_update_with_impersonation.yml")

          // after second reload dev1 & dev2 can access indices
          dev1SearchManagers.foreach(assertAllowedSearch(_, "test1_index"))
          dev1SearchManagers.foreach(assertIndexNotFound(_, "test2_index"))
          dev2SearchManagers.foreach(assertIndexNotFound(_, "test1_index"))
          dev2SearchManagers.foreach(assertAllowedSearch(_, "test2_index"))
        }
        "settings are valid and response with warnings" in {
          val warningsJson = ujson.read(
            """
              |[
              |  {
              |    "block_name": "test1",
              |    "rule_name": "auth_key_sha256",
              |    "message": "The rule contains fully hashed username and password. It doesn't support impersonation in this use case.",
              |    "hint": "You can use second version of the rule and use not hashed username. Like that: `auth_key_sha256: USER_NAME:hash(PASSWORD)"
              |  }
              |]
              |""".stripMargin
          )

          def forceReload(rorSettingsResource: String, warnings: ujson.Value): Unit = {
            val testSettingsYaml = getResourceContent(rorSettingsResource)
            updateRorTestSettings(rorClients.head, testSettingsYaml, 30 minutes, warnings)

            // check if settings are present in index
            assertTestSettingsInIndex(
              expectedSettings = testSettingsYaml,
              expectedTtl = 30 minutes
            )

            eventually { // await until all nodes load settings
              rorClients.foreach { rorApiManager =>
                val response = rorApiManager.currentRorTestSettings
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
          dev1SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test1_index"))

          val rorSettingsResource = "/admin_api/readonlyrest_with_warnings.yml"
          forceReload(rorSettingsResource, warningsJson)

          val testSettings = getResourceContent(rorSettingsResource)
          rorClients.foreach { rorApiManager =>
            assertTestSettingsPresent(
              rorApiManager,
              testSettings,
              "30 minutes",
              warningsJson
            )
          }

          // user with hashed credential cannot be impersonated
          dev1SearchManagers.foreach {
            assertImpersonationNotAllowed(_, "test1_index")
          }
        }
        "return local users" in {
          val testSettingsYaml = getResourceContent("/admin_api/readonlyrest_first_update_with_impersonation.yml")
          updateRorTestSettings(rorClients.head, testSettingsYaml, 30 minutes)

          assertTestSettingsInIndex(expectedSettings = testSettingsYaml, expectedTtl = 30 minutes)

          eventually { // await until all nodes load settings
            rorClients.foreach {
              assertTestSettingsPresent(_, testSettingsYaml, "30 minutes")
            }
          }

          rorClients.foreach { rorApiManager =>
            val response = rorApiManager.currentRorLocalUsers
            response should have statusCode 200
            response.responseJson("status").str should be("OK")
            (response.responseJson("unknown_users").bool, response.responseJson("users").arr.toSet.map(_.str)) should
              be(false, Set("dev1")) // admin is filtered out
          }
        }
      }
      "return info that settings were invalidated" in {
        def forceReload(rorSettingsResource: String): Unit = {
          val testSettings = getResourceContent(rorSettingsResource)
          updateRorTestSettings(rorClients.head, testSettings, 30 minutes)

          assertTestSettingsInIndex(expectedSettings = testSettings, expectedTtl = 30 minutes)

          eventually { // await until all nodes load settings
            rorClients.foreach { rorApiManager =>
              assertTestSettingsPresent(rorApiManager, testSettings, "30 minutes")
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
        dev1SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test1_index"))
        dev1SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test2_index"))
        dev2SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test1_index"))
        dev2SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test2_index"))

        // first reload
        forceReload("/admin_api/readonlyrest_first_update_with_impersonation.yml")

        // after first reload only dev1 can access indices
        dev1SearchManagers.foreach(assertAllowedSearch(_, "test1_index"))
        dev1SearchManagers.foreach(assertIndexNotFound(_, "test2_index"))
        dev2SearchManagers.foreach(assertOperationNotAllowed(_, "test1_index"))
        dev2SearchManagers.foreach(assertOperationNotAllowed(_, "test2_index"))

        // second reload
        val rorSettingsResource = "/admin_api/readonlyrest_second_update_with_impersonation.yml"
        forceReload(rorSettingsResource)

        // after second reload dev1 & dev2 can access indices
        dev1SearchManagers.foreach(assertAllowedSearch(_, "test1_index"))
        dev1SearchManagers.foreach(assertIndexNotFound(_, "test2_index"))
        dev2SearchManagers.foreach(assertIndexNotFound(_, "test1_index"))
        dev2SearchManagers.foreach(assertAllowedSearch(_, "test2_index"))

        invalidateRorTestSettings(rorClients.head)

        eventually {
          rorClients.foreach { rorApiManager =>
            val response = rorApiManager.currentRorTestSettings
            response should have statusCode 200
            response.responseJson("status").str should be("TEST_SETTINGS_INVALIDATED")
            response.responseJson("message").str should be("ROR Test settings are invalidated")
            response.responseJson("settings").str should be(getResourceContent(rorSettingsResource))
            response.responseJson("ttl").str should be("30 minutes")
          }
        }

        // after test core invalidation, impersonations requests should be rejected
        dev1SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test1_index"))
        dev1SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test2_index"))
        dev2SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test1_index"))
        dev2SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test2_index"))
      }
    }
    "provide a method for reload test settings engine" which {
      "is going to reload ROR test core with TTL" when {
        "settings are new and correct" in {
          def forceReload(rorSettingsResource: String, settingsTtl: FiniteDuration, settingsTtlString: String): Unit = {
            val testSettings = getResourceContent(rorSettingsResource)
            updateRorTestSettings(rorClients.head, testSettings, settingsTtl)

            assertTestSettingsInIndex(expectedSettings = testSettings, expectedTtl = settingsTtl)

            eventually { // await until all nodes load settings
              rorClients.foreach { rorApiManager =>
                assertTestSettingsPresent(rorApiManager, testSettings, settingsTtlString)
              }
            }
          }

          eventually { // await until all nodes load settings
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
          dev1SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test1_index"))
          dev1SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test2_index"))
          dev2SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test1_index"))
          dev2SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test2_index"))

          // first reload
          forceReload(
            rorSettingsResource = "/admin_api/readonlyrest_first_update_with_impersonation.yml",
            settingsTtl = 30 minutes,
            settingsTtlString = "30 minutes"
          )

          eventually { // await until all nodes load settings
            rorClients.foreach { rorApiManager =>
              assertTestSettingsPresent(
                rorApiManager = rorApiManager,
                testSettings = getResourceContent("/admin_api/readonlyrest_first_update_with_impersonation.yml"),
                expectedTtl = "30 minutes"
              )
            }
          }

          // after first reload only dev1 can access indices
          dev1SearchManagers.foreach(assertAllowedSearch(_, "test1_index"))
          dev1SearchManagers.foreach(assertIndexNotFound(_, "test2_index"))
          dev2SearchManagers.foreach(assertOperationNotAllowed(_, "test1_index"))
          dev2SearchManagers.foreach(assertOperationNotAllowed(_, "test2_index"))

          // second reload
          val rorSettingsResource = "/admin_api/readonlyrest_second_update_with_impersonation.yml"
          val settingsTtl = 15.seconds
          forceReload(
            rorSettingsResource = rorSettingsResource,
            settingsTtl = settingsTtl,
            settingsTtlString = "15 seconds"
          )

          eventually { // await until all nodes load settings
            rorClients.foreach { rorApiManager =>
              assertTestSettingsPresent(
                rorApiManager = rorApiManager,
                testSettings = getResourceContent(rorSettingsResource),
                expectedTtl = "15 seconds"
              )
            }
          }

          // after second reload dev1 & dev2 can access indices
          dev1SearchManagers.foreach(assertAllowedSearch(_, "test1_index"))
          dev1SearchManagers.foreach(assertIndexNotFound(_, "test2_index"))
          dev2SearchManagers.foreach(assertIndexNotFound(_, "test1_index"))
          dev2SearchManagers.foreach(assertAllowedSearch(_, "test2_index"))

          // wait for test engine auto-destruction
          Thread.sleep(settingsTtl.toMillis)

          rorClients.foreach { rorApiManager =>
            assertTestSettingsInvalidated(rorApiManager, getResourceContent(rorSettingsResource), "15 seconds")
          }

          dev1SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test1_index"))
          dev1SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test2_index"))
          dev2SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test1_index"))
          dev2SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test2_index"))
        }
        "settings are up to date and new ttl is passed" in {
          val testSettings = getResourceContent("/admin_api/readonlyrest_index.yml")
          updateRorTestSettings(rorClients.head, testSettings, 30 minutes)
          assertTestSettingsInIndex(expectedSettings = testSettings, expectedTtl = 30 minutes)

          eventually { // await until all nodes load settings
            rorClients.foreach {
              assertTestSettingsPresent(_, testSettings, "30 minutes")
            }
          }

          val timestamps =
            rorClients
              .map(_.currentRorTestSettings.responseJson("valid_to").str)
              .map(Instant.parse).toSet

          timestamps.size should be(1)

          // wait for valid_to comparison purpose
          Thread.sleep(100)

          updateRorTestSettings(rorClients.head, testSettings, 45 minutes)
          assertTestSettingsInIndex(expectedSettings = testSettings, expectedTtl = 45 minutes)

          eventually { // await until all nodes load settings
            rorClients.foreach {
              assertTestSettingsPresent(_, testSettings, "45 minutes")
            }
          }

          val timestampsAfterReload =
            rorClients
              .map(_.currentRorTestSettings.responseJson("valid_to").str)
              .map(Instant.parse)
              .toSet
          timestampsAfterReload.size should be(1)

          timestampsAfterReload.head.isAfter(timestamps.head) should be(true)
        }
      }
      "return info that request is malformed" when {
        "ttl missing" in {
          val settings = getResourceContent("/admin_api/readonlyrest_index.yml")
          val requestBody = s"""{"settings": "${escapeJava(settings)}"}"""

          rorClients.foreach { rorApiManager =>
            val result = rorApiManager
              .updateRorTestSettingsRaw(rawRequestBody = requestBody)

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
              .updateRorTestSettingsRaw(rawRequestBody = requestBody)

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
          val settings = getResourceContent("/admin_api/readonlyrest_index.yml")
          val requestBody = s"""{"settings": "${escapeJava(settings)}", "ttl": "30 units"}"""

          rorClients.foreach { rorApiManager =>
            val result = rorApiManager
              .updateRorTestSettingsRaw(rawRequestBody = requestBody)

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
      "return info that settings are malformed" when {
        "invalid YAML is provided" in {
          rorClients.foreach { rorApiManager =>
            val result = rorApiManager
              .updateRorTestSettings(getResourceContent("/admin_api/readonlyrest_malformed.yml"))

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
              .updateRorTestSettings(getResourceContent("/admin_api/readonlyrest_with_ldap.yml"))

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
    "provide a method for test settings engine invalidation" which {
      "will destruct the engine on demand" in {
        def forceReload(rorSettingsResource: String): Unit = {
          val testSettings = getResourceContent(rorSettingsResource)

          updateRorTestSettings(rorClients.head, testSettings, 30 minutes)

          assertTestSettingsInIndex(
            expectedSettings = testSettings,
            expectedTtl = 30 minutes
          )

          eventually { // await until all nodes load settings
            rorClients.foreach { rorApiManager =>
              assertTestSettingsPresent(rorApiManager, testSettings, "30 minutes")
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

        dev1SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test1_index"))
        dev1SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test2_index"))
        dev2SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test1_index"))
        dev2SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test2_index"))

        // first reload
        forceReload("/admin_api/readonlyrest_first_update_with_impersonation.yml")

        // after first reload only dev1 can access indices
        dev1SearchManagers.foreach(assertAllowedSearch(_, "test1_index"))
        dev1SearchManagers.foreach(assertIndexNotFound(_, "test2_index"))
        dev2SearchManagers.foreach(assertOperationNotAllowed(_, "test1_index"))
        dev2SearchManagers.foreach(assertOperationNotAllowed(_, "test2_index"))

        // second reload
        forceReload("/admin_api/readonlyrest_second_update_with_impersonation.yml")

        // after second reload dev1 & dev2 can access indices
        dev1SearchManagers.foreach(assertAllowedSearch(_, "test1_index"))
        dev1SearchManagers.foreach(assertIndexNotFound(_, "test2_index"))
        dev2SearchManagers.foreach(assertIndexNotFound(_, "test1_index"))
        dev2SearchManagers.foreach(assertAllowedSearch(_, "test2_index"))

        invalidateRorTestSettings(rorClients.head)

        Thread.sleep(settingsReloadInterval.toMillis) // wait for engines reload

        // wait for engines reload
        rorClients.foreach { rorApiManager =>
          assertTestSettingsInvalidated(
            rorApiManager = rorApiManager,
            testSettingsYaml = getResourceContent("/admin_api/readonlyrest_second_update_with_impersonation.yml"),
            expectedTtl = "30 minutes"
          )
        }

        // after test core invalidation, impersonations requests should be rejected
        dev1SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test1_index"))
        dev1SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test2_index"))
        dev2SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test1_index"))
        dev2SearchManagers.foreach(assertTestSettingsNotConfigured(_, "test2_index"))
      }
    }
    "main ROR settings and test ROR settings coexistence check" when {
      "get main ROR index settings" should {
        "return no index settings" when {
          "no main and test settings in the index" in {
            adminIndexManager.removeIndex(readonlyrestIndexName)
            rorClients.foreach { rorApiManager =>
              assertNoRorSettingsInIndex(rorApiManager)
              assertTestSettingsNotConfigured(rorApiManager)
            }
          }
          "only test settings in the index" in {
            def forceReloadTestSettings(testSettings: String): Unit = {
              updateRorTestSettings(rorClients.head, testSettings, 30 minutes)
              assertTestSettingsInIndex(
                expectedSettings = testSettings,
                expectedTtl = 30 minutes
              )
            }

            adminIndexManager.removeIndex(readonlyrestIndexName)

            rorClients.foreach { rorApiManager =>
              assertNoRorSettingsInIndex(rorApiManager)
              assertTestSettingsNotConfigured(rorApiManager)
            }

            val settings = getResourceContent("/admin_api/readonlyrest_first_update_with_impersonation.yml")
            forceReloadTestSettings(settings)
            Thread.sleep(settingsReloadInterval.toMillis) // wait for engines reload

            rorClients.foreach { rorApiManager =>
              assertNoDocumentWithMainRorSettingsInIndex(rorApiManager)
              assertTestSettingsPresent(
                rorApiManager,
                testSettings = settings,
                expectedTtl = "30 minutes"
              )
            }
          }
        }
        "return index settings" when {
          "only main settings in the index" in {
            def forceReloadMainSettings(mainSettingsYaml: String) = {
              updateRorMainSettings(rorClients.head, mainSettingsYaml)
              assertSettingsInIndex(expectedSettings = mainSettingsYaml)
            }

            adminIndexManager.removeIndex(readonlyrestIndexName)

            rorClients.foreach { rorApiManager =>
              assertNoRorSettingsInIndex(rorApiManager)
              assertTestSettingsNotConfigured(rorApiManager)
            }

            val settings = getResourceContent("/admin_api/readonlyrest_first_update_with_impersonation.yml")
            forceReloadMainSettings(settings)

            rorClients.foreach { rorApiManager =>
              assertInIndexSettingsPresent(rorApiManager, settings)
              assertTestSettingsNotConfigured(rorApiManager)
            }
          }
          "main and test settings in the index" in {
            def forceReloadMainSettings(settings: String) = {
              updateRorMainSettings(rorClients.head, settings)
              assertSettingsInIndex(expectedSettings = settings)
            }

            def forceReloadTestSettings(testSettings: String): Unit = {
              updateRorTestSettings(rorClients.head, testSettings, 30 minutes)
              assertTestSettingsInIndex(
                expectedSettings = testSettings,
                expectedTtl = 30 minutes
              )
            }

            adminIndexManager.removeIndex(readonlyrestIndexName)

            rorClients.foreach { rorApiManager =>
              assertNoRorSettingsInIndex(rorApiManager)
              assertTestSettingsNotConfigured(rorApiManager)
            }

            val settings = getResourceContent("/admin_api/readonlyrest_first_update_with_impersonation.yml")
            forceReloadMainSettings(settings)
            forceReloadTestSettings(settings)

            Thread.sleep(settingsReloadInterval.toMillis) // wait for engines reload
            rorClients.foreach { client =>
              assertInIndexSettingsPresent(client, settings)
              assertTestSettingsPresent(client, testSettings = settings, expectedTtl = "30 minutes")
            }
          }
        }
      }
    }
  }

  override protected def beforeEach(): Unit = {
    // back to settings loaded on container start
    rorWithNoIndexSettingsAdminActionManager
      .updateRorInIndexSettings(getResourceContent("/admin_api/readonlyrest.yml"))
      .force()

    new IndexManager(ror2_1Node.adminClient, esVersionUsed).removeIndex(readonlyrestIndexName)

    adminIndexManager.removeIndex(readonlyrestIndexName)

    ror1WithIndexSettingsAdminActionManager
      .updateRorInIndexSettings(getResourceContent("/admin_api/readonlyrest_index.yml"))
      .force()

    eventually { // await until all nodes invalidate the settings
      rorClients.foreach(assertTestSettingsNotConfigured)
    }
  }

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = settingsReloadInterval.plus(10 seconds), interval = 1 second)

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

  private def assertSettingsInIndex(expectedSettings: String) = {
    val indexSearchResponse = adminSearchManager.search(readonlyrestIndexName)
    indexSearchResponse should have statusCode 200
    val indexSearchHits = indexSearchResponse.responseJson("hits")("hits").arr.toList
    indexSearchHits.size should be >= 1 // at least main document or test document should be present
    val testSettingsDocumentHit = indexSearchHits.find { searchResult =>
      (searchResult("_index").str, searchResult("_id").str) === (readonlyrestIndexName, "1")
    }.value

    val testSettingsDocumentContent = testSettingsDocumentHit("_source")
    testSettingsDocumentContent("settings").str should be(expectedSettings)
  }

  private def assertTestSettingsInIndex(expectedSettings: String, expectedTtl: FiniteDuration) = {
    val indexSearchResponse = adminSearchManager.search(readonlyrestIndexName)
    indexSearchResponse should have statusCode 200
    val indexSearchHits = indexSearchResponse.responseJson("hits")("hits").arr.toList
    indexSearchHits.size should be >= 1 // at least main document or test document should be present
    val testSettingsDocumentHit = indexSearchHits
      .find { searchResult =>
        (searchResult("_index").str, searchResult("_id").str) === (readonlyrestIndexName, testSettingsEsDocumentId)
      }
      .value

    val testSettingsDocumentContent = testSettingsDocumentHit("_source")
    testSettingsDocumentContent("settings").str should be(expectedSettings)
    testSettingsDocumentContent("expiration_ttl_millis").str should be(expectedTtl.toMillis.toString)
    testSettingsDocumentContent("expiration_timestamp").str.isInIsoDateTimeFormat should be(true)

    val mocksContent = testSettingsDocumentContent("auth_services_mocks")
    mocksContent("ldapMocks").obj.isEmpty should be(true)
    mocksContent("externalAuthenticationMocks").obj.isEmpty should be(true)
    mocksContent("externalAuthorizationMocks").obj.isEmpty should be(true)
  }

  private def assertNoRorSettingsInIndex(rorApiManager: RorApiManager) = {
    val result = rorApiManager.getRorInIndexSettings
    result should have statusCode 200
    result.responseJson should be(ujson.read(
      """
        |{
        |  "status": "empty",
        |  "message": "Cannot find ReadonlyREST settings index"
        |}
        |""".stripMargin
    ))
  }

  private def assertNoDocumentWithMainRorSettingsInIndex(rorApiManager: RorApiManager) = {
    val result = rorApiManager.getRorInIndexSettings
    result should have statusCode 200
    result.responseJson should be(ujson.read(
      """
        |{
        |  "status": "ko",
        |  "message": "Cannot found document with ReadonlyREST settings"
        |}
        |""".stripMargin
    ))
  }

  private def assertInIndexSettingsPresent(rorApiManager: RorApiManager, settings: String) = {
    val result = rorApiManager.getRorInIndexSettings
    result should have statusCode 200
    result.responseJson("status").str should be("ok")
    result.responseJson("message").str should be(settings)
  }

  private def assertTestSettingsNotConfigured(rorApiManager: RorApiManager) = {
    val response = rorApiManager.currentRorTestSettings
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
                                        testSettings: String,
                                        expectedTtl: String,
                                        expectedWarningsJson: Value = ujson.read("[]")) = {
    val response = rorApiManager.currentRorTestSettings
    response should have statusCode 200
    response.responseJson("status").str should be("TEST_SETTINGS_PRESENT")
    response.responseJson("settings").str should be(testSettings)
    response.responseJson("ttl").str should be(expectedTtl)
    response.responseJson("valid_to").str.isInIsoDateTimeFormat should be(true)
    response.responseJson("warnings") should be(expectedWarningsJson)
  }

  private def assertTestSettingsInvalidated(rorApiManager: RorApiManager,
                                            testSettingsYaml: String,
                                            expectedTtl: String) = {
    val response = rorApiManager.currentRorTestSettings
    response should have statusCode 200
    response.responseJson("status").str should be("TEST_SETTINGS_INVALIDATED")
    response.responseJson("message").str should be("ROR Test settings are invalidated")
    response.responseJson("settings").str should be(testSettingsYaml)
    response.responseJson("ttl").str should be(expectedTtl)
  }

  private def updateRorTestSettings(rorApiManager: RorApiManager,
                                    testSettingsYaml: String,
                                    settingsTtl: FiniteDuration,
                                    expectedWarningsJson: Value = ujson.read("[]")) = {
    val response = rorApiManager.updateRorTestSettings(testSettingsYaml, settingsTtl)
    response should have statusCode 200
    response.responseJson("status").str should be("OK")
    response.responseJson("message").str should be("updated settings")
    response.responseJson("valid_to").str.isInIsoDateTimeFormat should be(true)
    response.responseJson("warnings") should be(expectedWarningsJson)
  }

  private def updateRorMainSettings(rorApiManager: RorApiManager, mainSettingsYaml: String) = {
    val result = rorApiManager.updateRorInIndexSettings(mainSettingsYaml)
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

  private def invalidateRorTestSettings(rorApiManager: RorApiManager) = {
    val response = rorApiManager.invalidateRorTestSettings()
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

  private def assertTestSettingsNotConfigured(sm: SearchManager, indexName: String) = {
    val results = sm.search(indexName)
    results should have statusCode 403
    results.responseJson should be(ujson.read(
      """
        |{
        |  "error":{
        |    "root_cause":[
        |      {
        |        "type":"forbidden_response",
        |        "reason":"Forbidden by ReadonlyREST",
        |        "due_to":"TEST_SETTINGS_NOT_CONFIGURED"
        |      }
        |    ],
        |    "type":"forbidden_response",
        |    "reason":"Forbidden by ReadonlyREST",
        |    "due_to":"TEST_SETTINGS_NOT_CONFIGURED"
        |  },
        |  "status":403
        |}
        |""".stripMargin
    ))
  }

  private def assertAllowedSearch(sm: SearchManager, indexName: String) = {
    val results = sm.search(indexName)
    results should have statusCode 200
  }

  private def assertIndexNotFound(sm: SearchManager, indexName: String) = {
    val results = sm.search(indexName)
    results should have statusCode 404

    val generatedIndex = results.responseJson("error")("index").str
    generatedIndex should fullyMatch regex s"^${indexName}_ROR_[a-zA-Z0-9]{10}$$".r

    val reason =
      if (Version.greaterOrEqualThan(esVersionUsed, 7, 0, 0)) {
        s"no such index [$generatedIndex]"
      } else {
        "no such index"
      }

    results.responseJson should be(ujson.read(
      s"""
         |{
         |  "error":{
         |    "root_cause":[
         |      {
         |        "type":"index_not_found_exception",
         |        "reason":"$reason",
         |        "resource.type":"index_or_alias",
         |        "resource.id":"$generatedIndex",
         |        "index_uuid":"_na_",
         |        "index":"$generatedIndex"
         |      }
         |    ],
         |    "type":"index_not_found_exception",
         |    "reason":"$reason",
         |    "resource.type":"index_or_alias",
         |    "resource.id":"$generatedIndex",
         |    "index_uuid":"_na_",
         |    "index":"$generatedIndex"
         |  },
         |  "status":404
         |}""".stripMargin
    ))
  }

  private def assertOperationNotAllowed(sm: SearchManager, indexName: String) = {
    val results = sm.search(indexName)
    results should have statusCode 403
    results.responseJson should be(ujson.read(
      """
        |{
        |  "error":{
        |    "root_cause":[
        |      {
        |        "type":"forbidden_response",
        |        "reason":"Forbidden by ReadonlyREST",
        |        "due_to":"OPERATION_NOT_ALLOWED"
        |      }
        |    ],
        |  "type":"forbidden_response",
        |  "reason":"Forbidden by ReadonlyREST",
        |  "due_to":"OPERATION_NOT_ALLOWED"
        |  },
        |  "status":403
        |}""".stripMargin
    ))
  }

  private def assertImpersonationNotAllowed(sm: SearchManager, indexName: String) = {
    val results = sm.search(indexName)
    results should have statusCode 403
    results.responseJson should be(ujson.read(
      """
        |{
        |  "error":{
        |    "root_cause":[
        |      {
        |        "type":"forbidden_response",
        |        "reason":"Forbidden by ReadonlyREST",
        |        "due_to":"IMPERSONATION_NOT_ALLOWED"
        |      }
        |    ],
        |  "type":"forbidden_response",
        |  "reason":"Forbidden by ReadonlyREST",
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
