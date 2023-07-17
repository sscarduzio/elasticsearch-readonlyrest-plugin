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
import eu.timepit.refined.auto._
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{BeforeAndAfterEach, OptionValues}
import tech.beshu.ror.integration.suites.base.support.BaseManyEsClustersIntegrationTest
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyWordSpecLike, PluginTestSupport, SingletonLdapContainers}
import tech.beshu.ror.utils.containers.SecurityType.RorWithXpackSecurity
import tech.beshu.ror.utils.containers._
import tech.beshu.ror.utils.containers.dependencies.{ldap, wiremock}
import tech.beshu.ror.utils.containers.images.ReadonlyRestWithEnabledXpackSecurityPlugin.Config.Attributes
import tech.beshu.ror.utils.containers.images.domain.Enabled
import tech.beshu.ror.utils.elasticsearch.{IndexManager, RorApiManager, SearchManager}
import tech.beshu.ror.utils.misc.Resources.getResourceContent
import ujson.Value.Value

import scala.concurrent.duration._
import scala.language.postfixOps

class AdminApiAuthMockSuite
  extends AnyWordSpec
    with BaseManyEsClustersIntegrationTest
    with PluginTestSupport
    with ESVersionSupportForAnyWordSpecLike
    with BeforeAndAfterEach
    with Eventually
    with OptionValues
    with Matchers {

  override lazy val clusterContainers = NonEmptyList.of(esCluster)
  override lazy val esTargets = NonEmptyList.fromListUnsafe(esCluster.nodes)

  override implicit val rorConfigFileName: String = "/admin_api_mocks/readonlyrest.yml"

  private val readonlyrestIndexName = ".readonlyrest"

  private val esCluster: EsClusterContainer = createLocalClusterContainer(
    EsClusterSettings.create(
      clusterName = "ROR1",
      numberOfInstances = 2,
      securityType = RorWithXpackSecurity(Attributes.default.copy(
        rorConfigReloading = Enabled.Yes(2 seconds),
        rorCustomSettingsIndex = Some(readonlyrestIndexName),
        rorConfigFileName = rorConfigFileName
      )),
      nodeDataInitializer = NoOpElasticsearchNodeDataInitializer,
      dependentServicesContainers = clusterDependencies
    )
  )

  private def clusterDependencies: List[DependencyDef] = List(
    ldap(name = "LDAP1", SingletonLdapContainers.ldap1),
    ldap(name = "LDAP2", SingletonLdapContainers.ldap2),
    wiremock(name = "EXT1", mappings = "/impersonation/wiremock_service1_ext_user_1.json", "/impersonation/wiremock_group_provider1_gpa_user_1.json"),
    wiremock(name = "EXT1", mappings = "/impersonation/wiremock_service2_ext_user_2.json", "/impersonation/wiremock_group_provider2_gpa_user_2.json"),
  )

  private val testEngineReloadInterval = 2 seconds
  private val testSettingsEsDocumentId = "2"

  "An admin Auth Mock REST API" should {
    "return info that test settings are not configured" when {
      "get current mocks" in {
        rorClients.foreach { rorApiManager =>
          val response = rorApiManager.currentMockedServices()
          (response.responseCode, response.responseJson) should be(200, testSettingsNotConfiguredJson)
        }
      }
      "update mocks" in {
        val payloadServices = ujson.read(
          s"""
             |[
             |  {
             |    "type": "LDAP",
             |    "name": "ldap1",
             |    "mock": "NOT_CONFIGURED"
             |  }
             |]
             |""".stripMargin
        )

        rorClients.foreach { rorApiManager =>
          val response = rorApiManager.configureImpersonationMocks(updateMocksPayload(payloadServices))
          (response.responseCode, response.responseJson) should be(200, testSettingsNotConfiguredJson)
        }
      }
    }
    "provide a method for get current mocked services" which {
      "return info that test settings are invalidated" when {
        "get current test settings" in {
          setupTestSettingsOnAllNodes()

          invalidateTestSettingsOnAllNodes()

          rorClients.foreach { rorApiManager =>
            val response = rorApiManager.currentMockedServices()
            response.responseCode should be(200)
            response.responseJson("status").str should be("TEST_SETTINGS_INVALIDATED")
            response.responseJson("message").str should be("ROR Test settings are invalidated. To use Auth Services Mock ROR has to have Test settings active.")
          }
        }
      }
      "return info that mocks are not configured" in {
        setupTestSettingsOnAllNodes()

        rorClients.head
          .invalidateImpersonationMocks()
          .forceOk()

        eventually {
          rorClients.foreach { rorApiManager =>
            val response = rorApiManager.currentMockedServices()
            response.responseCode should be(200)
            response.responseJson("status").str should be("TEST_SETTINGS_PRESENT")
            response.responseJson("services") should be(ujson.read(
              s"""
                 |[
                 |  {
                 |    "type": "LDAP",
                 |    "name": "ldap1",
                 |    "mock": "NOT_CONFIGURED"
                 |  },
                 |  {
                 |    "type": "LDAP",
                 |    "name": "ldap2",
                 |    "mock": "NOT_CONFIGURED"
                 |  },
                 |  {
                 |    "type": "EXT_AUTHN",
                 |    "name": "ext1",
                 |    "mock": "NOT_CONFIGURED"
                 |  },
                 |  {
                 |    "type": "EXT_AUTHN",
                 |    "name": "ext2",
                 |    "mock": "NOT_CONFIGURED"
                 |  },
                 |  {
                 |    "type": "EXT_AUTHZ",
                 |    "name": "grp1",
                 |    "mock": "NOT_CONFIGURED"
                 |  },
                 |  {
                 |    "type": "EXT_AUTHZ",
                 |    "name": "grp2",
                 |    "mock": "NOT_CONFIGURED"
                 |  }
                 |]
                 |""".stripMargin))
          }
        }

        assertAuthMocksInIndex(ujson.read(
          s"""
             |{
             |  "ldapMocks": {},
             |  "externalAuthenticationMocks": {},
             |  "externalAuthorizationMocks": {}
             |}
             |""".stripMargin
        ))

        rorClients.foreach { rorApiManager =>
          val testConfigResponse = rorApiManager.currentRorTestConfig
          testConfigResponse.responseJson("status").str should be("TEST_SETTINGS_PRESENT")
          testConfigResponse.responseJson("warnings") should be(
            ujson.read(
              s"""
                 |[
                 |  {
                 |    "block_name": "test2 (1)",
                 |    "rule_name": "auth_key_sha1",
                 |    "message": "The rule contains fully hashed username and password. It doesn't support impersonation in this configuration",
                 |    "hint": "You can use second version of the rule and use not hashed username. Like that: `auth_key_sha1: USER_NAME:hash(PASSWORD)"
                 |  },
                 |  {
                 |    "block_name": "test3 (1)",
                 |    "rule_name": "ldap_auth",
                 |    "message": "The rule 'ldap_auth' will fail to match the impersonating request when the mock of the service 'ldap1' is not configured",
                 |    "hint": "Configure a mock of an LDAP service with ID [ldap1]"
                 |  },
                 |  {
                 |    "block_name": "test3 (2)",
                 |    "rule_name": "external_authentication",
                 |    "message": "The rule 'external_authentication' will fail to match the impersonating request when the mock of the service 'ext1' is not configured",
                 |    "hint": "Configure a mock of an external authentication service with ID [ext1]"
                 |  },
                 |  {
                 |    "block_name": "test3 (3)",
                 |    "rule_name": "groups_provider_authorization",
                 |    "message": "The rule 'groups_provider_authorization' will fail to match the impersonating request when the mock of the service 'grp1' is not configured",
                 |    "hint": "Configure a mock of an external authorization service with ID [grp1]"
                 |  }
                 |]
                 |""".stripMargin
            )
          )
        }
      }
      "return info that some of mocks are configured" in {
        setupTestSettingsOnAllNodes()

        val payloadServices = ujson.read(
          s"""
             |[
             |  {
             |    "type": "LDAP",
             |    "name": "ldap1",
             |    "mock": {
             |      "users": [
             |        {
             |          "name": "JohnDoe",
             |          "groups": [
             |            "Developer",
             |            "DevOps"
             |          ]
             |        },
             |        {
             |          "name": "RobertSmith",
             |          "groups": [
             |            "Manager"
             |          ]
             |        }
             |      ]
             |    }
             |  },
             |  {
             |    "type": "LDAP",
             |    "name": "ldap2",
             |    "mock": {
             |      "users": [
             |        {
             |          "name": "JohnDoe",
             |          "groups": [
             |            "DevOps"
             |          ]
             |        },
             |        {
             |          "name": "JudyBrown",
             |          "groups": [
             |            "Customer"
             |          ]
             |        }
             |      ]
             |    }
             |  },
             |  {
             |    "type": "EXT_AUTHN",
             |    "name": "ext1",
             |    "mock": "NOT_CONFIGURED"
             |  },
             |  {
             |    "type": "EXT_AUTHN",
             |    "name": "ext2",
             |    "mock": "NOT_CONFIGURED"
             |  },
             |  {
             |    "type": "EXT_AUTHZ",
             |    "name": "grp1",
             |    "mock": "NOT_CONFIGURED"
             |  },
             |  {
             |    "type": "EXT_AUTHZ",
             |    "name": "grp2",
             |    "mock": "NOT_CONFIGURED"
             |  }
             |]
             |""".stripMargin)

        rorClients.head
          .configureImpersonationMocks(updateMocksPayload(payloadServices))
          .forceOk()

        assertAuthMocksInIndex(ujson.read(
          s"""
             |{
             |  "ldapMocks": {
             |    "ldap1": {
             |      "users": [
             |        {
             |          "id": "JohnDoe",
             |          "groups": [
             |            "Developer",
             |            "DevOps"
             |          ]
             |        },
             |        {
             |          "id": "RobertSmith",
             |          "groups": [
             |            "Manager"
             |          ]
             |        }
             |      ]
             |    },
             |    "ldap2": {
             |      "users": [
             |        {
             |          "id": "JohnDoe",
             |          "groups": [
             |            "DevOps"
             |          ]
             |        },
             |        {
             |          "id": "JudyBrown",
             |          "groups": [
             |            "Customer"
             |          ]
             |        }
             |      ]
             |    }
             |  },
             |  "externalAuthenticationMocks": {},
             |  "externalAuthorizationMocks": {}
             |}
             |""".stripMargin
        ))

        eventually {
          rorClients.foreach { rorApiManager =>
            val response = rorApiManager.currentMockedServices()
            response.responseCode should be(200)
            response.responseJson("status").str should be("TEST_SETTINGS_PRESENT")
            response.responseJson("services") should be(payloadServices)
          }
        }
      }
      "return info that all mocks are configured" in {
        setupTestSettingsOnAllNodes()

        val payloadServices = ujson.read(
          s"""
             |[
             |  {
             |    "type": "LDAP",
             |    "name": "ldap1",
             |    "mock": {
             |      "users": [
             |        {
             |          "name": "JohnDoe",
             |          "groups": [
             |            "Developer",
             |            "DevOps"
             |          ]
             |        },
             |        {
             |          "name": "RobertSmith",
             |          "groups": [
             |            "Manager"
             |          ]
             |        }
             |      ]
             |    }
             |  },
             |  {
             |    "type": "LDAP",
             |    "name": "ldap2",
             |    "mock": {
             |      "users": [
             |        {
             |          "name": "JohnDoe",
             |          "groups": [
             |            "DevOps"
             |          ]
             |        },
             |        {
             |          "name": "JudyBrown",
             |          "groups": [
             |            "Customer"
             |          ]
             |        }
             |      ]
             |    }
             |  },
             |  {
             |    "type": "EXT_AUTHN",
             |    "name": "ext1",
             |    "mock": {
             |      "users": [
             |        {
             |          "name": "JaimeRhynes"
             |        }
             |      ]
             |    }
             |  },
             |  {
             |    "type": "EXT_AUTHN",
             |    "name": "ext2",
             |    "mock": {
             |      "users": [
             |        {
             |          "name": "MichaelDavis"
             |        },
             |        {
             |          "name": "Johny"
             |        }
             |      ]
             |    }
             |  },
             |  {
             |    "type": "EXT_AUTHZ",
             |    "name": "grp1",
             |    "mock": {
             |      "users": [
             |        {
             |          "name": "JaimeRhynes",
             |          "groups": [
             |            "Customer"
             |          ]
             |        }
             |      ]
             |    }
             |  },
             |  {
             |    "type": "EXT_AUTHZ",
             |    "name": "grp2",
             |    "mock": {
             |      "users": [
             |        {
             |          "name": "Martian",
             |          "groups": [
             |            "Visitor"
             |          ]
             |        }
             |      ]
             |    }
             |  }
             |]
             |""".stripMargin
        )

        rorClients.head
          .configureImpersonationMocks(updateMocksPayload(payloadServices))
          .forceOk()

        assertAuthMocksInIndex(ujson.read(
          s"""
             |{
             |  "ldapMocks": {
             |    "ldap1": {
             |      "users": [
             |        {
             |          "id": "JohnDoe",
             |          "groups": [
             |            "Developer",
             |            "DevOps"
             |          ]
             |        },
             |        {
             |          "id": "RobertSmith",
             |          "groups": [
             |            "Manager"
             |          ]
             |        }
             |      ]
             |    },
             |    "ldap2": {
             |      "users": [
             |        {
             |          "id": "JohnDoe",
             |          "groups": [
             |            "DevOps"
             |          ]
             |        },
             |        {
             |          "id": "JudyBrown",
             |          "groups": [
             |            "Customer"
             |          ]
             |        }
             |      ]
             |    }
             |  },
             |  "externalAuthenticationMocks": {
             |    "ext1": {
             |      "users": [
             |        {
             |          "id": "JaimeRhynes"
             |        }
             |      ]
             |    },
             |    "ext2": {
             |      "users": [
             |        {
             |          "id": "MichaelDavis"
             |        },
             |        {
             |          "id": "Johny"
             |        }
             |      ]
             |    }
             |  },
             |  "externalAuthorizationMocks": {
             |    "grp1": {
             |      "users": [
             |        {
             |          "id": "JaimeRhynes",
             |          "groups": [
             |            "Customer"
             |          ]
             |        }
             |      ]
             |    },
             |    "grp2": {
             |      "users": [
             |        {
             |          "id": "Martian",
             |          "groups": [
             |            "Visitor"
             |          ]
             |        }
             |      ]
             |    }
             |  }
             |}
             |""".stripMargin
        ))

        eventually { // await until all nodes load config
          rorClients.foreach { rorApiManager =>
            val response = rorApiManager.currentMockedServices()
            (response.responseCode, response.responseJson("status").str) should be(200, "TEST_SETTINGS_PRESENT")
            response.responseJson("services") should be(payloadServices)
          }
        }

        rorClients.foreach { rorApiManager =>
          val response = rorApiManager.currentRorTestConfig
          (response.responseCode, response.responseJson("status").str) should be(200, "TEST_SETTINGS_PRESENT")
          response.responseJson("warnings") should be(ujson.read(
            s"""
               |[
               |  {
               |    "block_name": "test2 (1)",
               |    "rule_name": "auth_key_sha1",
               |    "message": "The rule contains fully hashed username and password. It doesn't support impersonation in this configuration",
               |    "hint": "You can use second version of the rule and use not hashed username. Like that: `auth_key_sha1: USER_NAME:hash(PASSWORD)"
               |  }
               |]
               |""".stripMargin
          ))
        }
      }
    }
    "provide a method for reload mocked services" which {
      "is going to reload mocked services" when {
        "configuration is correct" when {
          "all services are passed" in {
            setupTestSettingsOnAllNodes()

            val payloadServices = ujson.read(
              s"""
                 |[
                 |  {
                 |    "type": "LDAP",
                 |    "name": "ldap1",
                 |    "mock": {
                 |      "users": [
                 |        {
                 |          "name": "JohnDoe",
                 |          "groups": [
                 |            "Developer",
                 |            "DevOps"
                 |          ]
                 |        },
                 |        {
                 |          "name": "RobertSmith",
                 |          "groups": [
                 |            "Manager"
                 |          ]
                 |        }
                 |      ]
                 |    }
                 |  },
                 |  {
                 |    "type": "LDAP",
                 |    "name": "ldap2",
                 |    "mock": {
                 |      "users": [
                 |        {
                 |          "name": "JohnDoe",
                 |          "groups": [
                 |            "DevOps"
                 |          ]
                 |        },
                 |        {
                 |          "name": "JudyBrown",
                 |          "groups": [
                 |            "Customer"
                 |          ]
                 |        }
                 |      ]
                 |    }
                 |  },
                 |  {
                 |    "type": "EXT_AUTHN",
                 |    "name": "ext1",
                 |    "mock": {
                 |      "users": [
                 |        {
                 |          "name": "JaimeRhynes"
                 |        }
                 |      ]
                 |    }
                 |  },
                 |  {
                 |    "type": "EXT_AUTHN",
                 |    "name": "ext2",
                 |    "mock": {
                 |      "users": [
                 |        {
                 |          "name": "MichaelDavis"
                 |        },
                 |        {
                 |          "name": "Johny"
                 |        }
                 |      ]
                 |    }
                 |  },
                 |  {
                 |    "type": "EXT_AUTHZ",
                 |    "name": "grp1",
                 |    "mock": {
                 |      "users": [
                 |        {
                 |          "name": "JaimeRhynes",
                 |          "groups": [
                 |            "Customer"
                 |          ]
                 |        }
                 |      ]
                 |    }
                 |  },
                 |  {
                 |    "type": "EXT_AUTHZ",
                 |    "name": "grp2",
                 |    "mock": {
                 |      "users": [
                 |        {
                 |          "name": "Martian",
                 |          "groups": [
                 |            "Visitor"
                 |          ]
                 |        }
                 |      ]
                 |    }
                 |  }
                 |]
                 |""".stripMargin)

            rorClients.foreach { rorApiManager =>
              val response = rorApiManager.configureImpersonationMocks(updateMocksPayload(payloadServices))
              (response.responseCode, response.responseJson) should be(200, authMocksUpdatedJson)
            }
          }
          "only some services are passed" in {
            setupTestSettingsOnAllNodes()

            val payloadServices = ujson.read(
              s"""
                 |[
                 |  {
                 |    "type": "EXT_AUTHZ",
                 |    "name": "grp2",
                 |    "mock": {
                 |      "users": [
                 |        {
                 |          "name": "JohnDoe",
                 |          "groups": [
                 |            "Developer",
                 |            "DevOps"
                 |          ]
                 |        },
                 |        {
                 |          "name": "RobertSmith",
                 |          "groups": [
                 |            "Manager"
                 |          ]
                 |        }
                 |      ]
                 |    }
                 |  }
                 |]
                 |""".stripMargin)

            rorClients.foreach { rorApiManager =>
              val response = rorApiManager.configureImpersonationMocks(updateMocksPayload(payloadServices))
              (response.responseCode, response.responseJson) should be(200, authMocksUpdatedJson)
            }
          }
          "any of services are passed" in {
            setupTestSettingsOnAllNodes()

            rorClients.foreach { rorApiManager =>
              val response = rorApiManager.configureImpersonationMocks(updateMocksPayload(ujson.read("[]")))
              (response.responseCode, response.responseJson) should be(200, authMocksUpdatedJson)
            }
          }
        }
      }
      "return info that test settings are invalidated" in {
        setupTestSettingsOnAllNodes()

        invalidateTestSettingsOnAllNodes()

        val payloadServices = ujson.read(
          s"""
             |[
             |  {
             |    "type": "LDAP",
             |    "name": "ldap1",
             |    "mock": "NOT_CONFIGURED"
             |  }
             |]
             |""".stripMargin)

        rorClients.foreach { rorApiManager =>
          val response = rorApiManager.configureImpersonationMocks(updateMocksPayload(payloadServices))
          (response.responseCode, response.responseJson) should be(200, ujson.read(
            s"""
               |{
               |  "status": "TEST_SETTINGS_INVALIDATED",
               |  "message": "ROR Test settings are invalidated. To use Auth Services Mock ROR has to have Test settings active."
               |}
               |""".stripMargin
          ))
        }
      }
      "return info that unknown services detected" when {
        "unknown service passed" in {
          setupTestSettingsOnAllNodes()

          val payloadServices = ujson.read(
            s"""
               |[
               |  {
               |    "type": "LDAP",
               |    "name": "ldap3",
               |    "mock": "NOT_CONFIGURED"
               |  },
               |  {
               |    "type": "LDAP",
               |    "name": "ldap4",
               |    "mock": "NOT_CONFIGURED"
               |  }
               |]
               |""".stripMargin)

          rorClients.foreach { rorApiManager =>
            val response = rorApiManager.configureImpersonationMocks(updateMocksPayload(payloadServices))
            (response.responseCode, response.responseJson) should be(200, ujson.read(
              s"""
                 |{
                 |  "status": "UNKNOWN_AUTH_SERVICES_DETECTED",
                 |  "message": "ROR doesn't allow to configure unknown Auth Services. Only the ones used in ROR's Test settings can be configured. Unknown services: [ldap3,ldap4]"
                 |}
                 |""".stripMargin
            ))
          }
        }
      }
      "return info that request is malformed" when {
        "unknown service type" in {
          setupTestSettingsOnAllNodes()

          val malformedPayload = ujson.read(
            s"""
               |[
               |  {
               |    "type": "EXT_AUTHORIZATION",
               |    "name": "ext1",
               |    "mock": "NOT_CONFIGURED"
               |  }
               |]
               |""".stripMargin)

          rorClients.foreach { rorApiManager =>
            val response = rorApiManager.configureImpersonationMocks(updateMocksPayload(malformedPayload))
            (response.responseCode, response.responseJson) should be(400, ujson.read(
              s"""
                 |{
                 |  "status": "FAILED",
                 |  "message": "JSON body malformed: [Could not parse at : [DecodingFailure at : Unknown auth mock service type: EXT_AUTHORIZATION]]"
                 |}
                 |""".stripMargin
            ))
          }
        }
        "empty JSON is passed" in {
          setupTestSettingsOnAllNodes()

          rorClients.foreach { rorApiManager =>
            val response = rorApiManager.configureImpersonationMocks("{}")
            (response.responseCode, response.responseJson) should be(400, ujson.read(
              s"""
                 |{
                 |  "status": "FAILED",
                 |  "message": "JSON body malformed: [Could not parse at .services: [DecodingFailure at .services: Missing required field]]"
                 |}
                 |""".stripMargin
            ))
          }
        }
        "JSON with malformed ldap user objects" in {
          setupTestSettingsOnAllNodes()

          val malformedPayload = ujson.read(
            s"""
               |{
               |  "services": [
               |    {
               |      "type": "LDAP",
               |      "name": "ldap1",
               |      "mock": {
               |        "users": [
               |          {
               |            "n": "ldap_user_1",
               |            "g": ["group1", "group3"]
               |          }
               |        ]
               |      }
               |    }
               |  ]
               |}
             """.stripMargin
          )

          val expectedResponse = ujson.read(
            s"""
               |{
               |  "status": "FAILED",
               |  "message": "JSON body malformed: [Could not parse at .services: [DecodingFailure at .services: Got value '{\\\"services\\\":[{\\\"type\\\":\\\"LDAP\\\",\\\"name\\\":\\\"ldap1\\\",\\\"mock\\\":{\\\"users\\\":[{\\\"n\\\":\\\"ldap_user_1\\\",\\\"g\\\":[\\\"group1\\\",\\\"group3\\\"]}]}}]}' with wrong type, expecting array]]"
               |}
               |""".stripMargin
          )

          rorClients.foreach { rorApiManager =>
            val response = rorApiManager.configureImpersonationMocks(updateMocksPayload(malformedPayload))
            (response.responseCode, response.responseJson) should be(400, expectedResponse)
          }
        }
      }
    }
  }

  private def invalidateTestSettingsOnAllNodes(): Unit = {
    rorClients.head
      .invalidateRorTestConfig()
      .forceOk()

    eventually { // await until all nodes load config
      rorClients.foreach {
        assertTestSettings(_, expectedStatus = "TEST_SETTINGS_INVALIDATED")
      }
    }
  }

  private def assertTestSettings(rorApiManager: RorApiManager, expectedStatus: String) = {
    val response = rorApiManager.currentRorTestConfig
    (response.responseCode, response.responseJson("status").str) should be(200, expectedStatus)
  }

  private def updateMocksPayload(payloadServices: Value) = {
    ujson.read(
      s"""
         |{
         |  "services": $payloadServices
         |}
         |"""
        .stripMargin
    )
  }

  private def testEngineConfig(): String = esCluster.resolvedRorConfig(getResourceContent(rorConfigFileName))

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(timeout = testEngineReloadInterval.plus(2 second), interval = 1 second)

  override protected def beforeEach(): Unit = {
    rorClients.foreach {
      _
        .invalidateImpersonationMocks()
        .force()
    }

    removeRorIndexAndAwaitForNotSetTestConfig()
  }

  private def removeRorIndexAndAwaitForNotSetTestConfig(): Unit = {
    // remove index storing test config
    new IndexManager(clients.head.basicAuthClient("admin", "container"), esVersionUsed)
      .removeIndex(readonlyrestIndexName)

    eventually { // await until node invalidate the test config
      rorClients.foreach {
        assertTestSettings(_, expectedStatus = "TEST_SETTINGS_NOT_CONFIGURED")
      }
    }
  }

  private def assertAuthMocksInIndex(expectedMocks: Value) = {
    val adminSearchManager = new SearchManager(clients.head.basicAuthClient("admin", "container"))
    val indexSearchResponse = adminSearchManager.search(readonlyrestIndexName)
    indexSearchResponse.responseCode should be(200)
    val indexSearchHits = indexSearchResponse.responseJson("hits")("hits").arr.toList
    indexSearchHits.size should be >= 1 // at least main document or test document should be present
    val testSettingsDocumentHit = indexSearchHits.find { searchResult =>
      (searchResult("_index").str, searchResult("_id").str) === (readonlyrestIndexName, testSettingsEsDocumentId)
    }.value

    val mocksContent = ujson.read(testSettingsDocumentHit("_source")("auth_services_mocks").str)
    mocksContent should be(expectedMocks)
  }

  private def setupTestSettingsOnAllNodes(): Unit = {
    rorClients.head
      .updateRorTestConfig(testEngineConfig())
      .forceOk()

    eventually { // await until all nodes load config
      rorClients.foreach {
        assertTestSettings(_, expectedStatus = "TEST_SETTINGS_PRESENT")
      }
    }
  }

  private def rorClients: List[RorApiManager] = {
    clients
      .toList
      .map(_.adminClient)
      .map(new RorApiManager(_, esVersionUsed))
  }

  private lazy val testSettingsNotConfiguredJson = {
    ujson.read(
      s"""
         |{
         |  "status": "TEST_SETTINGS_NOT_CONFIGURED",
         |  "message": "ROR Test settings are not configured. To use Auth Services Mock ROR has to have Test settings active."
         |}
         |""".stripMargin
    )
  }

  private lazy val authMocksUpdatedJson = {
    ujson.read(
      s"""
         |{
         |  "status": "OK",
         |  "message": "Auth mock updated"
         |}
         |""".stripMargin
    )
  }
}
