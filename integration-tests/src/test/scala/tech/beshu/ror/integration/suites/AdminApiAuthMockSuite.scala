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

import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyWordSpecLike, SingletonLdapContainers}
import tech.beshu.ror.utils.containers._
import tech.beshu.ror.utils.containers.dependencies.{ldap, wiremock}
import tech.beshu.ror.utils.containers.providers.ResolvedRorConfigFileProvider
import tech.beshu.ror.utils.elasticsearch.{BaseManager, RorApiManager}
import ujson.Value.Value

trait AdminApiAuthMockSuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with ESVersionSupportForAnyWordSpecLike
    with BeforeAndAfterEach
    with Matchers {
  this: EsContainerCreator with ResolvedRorConfigFileProvider =>

  override implicit val rorConfigFileName: String = "/admin_api_mocks/readonlyrest.yml"
  private lazy val rorApiManager = new RorApiManager(rorAdminClient, esVersionUsed)

  override def clusterDependencies: List[DependencyDef] = List(
    ldap(name = "LDAP1", SingletonLdapContainers.ldap1),
    ldap(name = "LDAP2", SingletonLdapContainers.ldap2),
    wiremock(name = "EXT1", mappings = "/impersonation/wiremock_service1_ext_user_1.json", "/impersonation/wiremock_group_provider1_gpa_user_1.json"),
    wiremock(name = "EXT1", mappings = "/impersonation/wiremock_service2_ext_user_2.json", "/impersonation/wiremock_group_provider2_gpa_user_2.json"),
  )

  override def nodeDataInitializer: Option[ElasticsearchNodeDataInitializer] = None

  "An admin Auth Mock REST API" should {
    "return info that test settings are not configured" when {
      "get current mocks" in {
        val response = rorApiManager.currentMockedServices()
        response.responseCode should be(200)
        response.responseJson("status").str should be("TEST_SETTINGS_NOT_CONFIGURED")
        response.responseJson("message").str should be("ROR Test settings are not configured. To use Auth Services Mock ROR has to have Test settings active.")
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
             |""".stripMargin)
        val response = rorApiManager.configureImpersonationMocks(updateMocksPayload(payloadServices))
        response.responseCode should be(200)
        response.responseJson("status").str should be("TEST_SETTINGS_NOT_CONFIGURED")
        response.responseJson("message").str should be("ROR Test settings are not configured. To use Auth Services Mock ROR has to have Test settings active.")
      }
    }
    "provide a method for get current mocked services" which {
      "return info that test settings are invalidated" when {
        "get current test settings" in {
          rorApiManager
            .updateRorTestConfig(testEngineConfig())
            .forceOk()

          rorApiManager
            .invalidateRorTestConfig()
            .forceOk()

          val response = rorApiManager.currentMockedServices()
          response.responseCode should be(200)
          response.responseJson("status").str should be("TEST_SETTINGS_INVALIDATED")
          response.responseJson("message").str should be("ROR Test settings are invalidated. To use Auth Services Mock ROR has to have Test settings active.")
        }
      }
      "return info that mocks are not configured" in {
        rorApiManager
          .updateRorTestConfig(testEngineConfig())
          .forceOk()

        rorApiManager
          .invalidateImpersonationMocks()
          .forceOk()

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

        val testConfigResponse = rorApiManager.currentRorTestConfig
        testConfigResponse.responseJson("status").str should be("TEST_SETTINGS_PRESENT")
        testConfigResponse.responseJson("warnings").arr.size should be(4)

        assertTestConfigWarning(
          json = testConfigResponse.responseJson("warnings")(0),
          blockName = "test2 (1)",
          ruleName = "auth_key_sha1",
          message = "The rule contains fully hashed username and password. It doesn't support impersonation in this configuration",
          hint = "You can use second version of the rule and use not hashed username. Like that: `auth_key_sha1: USER_NAME:hash(PASSWORD)"
        )

        assertTestConfigWarning(
          json = testConfigResponse.responseJson("warnings")(1),
          blockName = "test3 (1)",
          ruleName = "ldap_auth",
          message = "The rule 'ldap_auth' will fail to match the impersonating request when the mock of the service 'ldap1' is not configured",
          hint = "Configure a mock of an LDAP service with ID [ldap1]"
        )

        assertTestConfigWarning(
          json = testConfigResponse.responseJson("warnings")(2),
          blockName = "test3 (2)",
          ruleName = "external_authentication",
          message = "The rule 'external_authentication' will fail to match the impersonating request when the mock of the service 'ext1' is not configured",
          hint = "Configure a mock of an external authentication service with ID [ext1]"
        )

        assertTestConfigWarning(
          json = testConfigResponse.responseJson("warnings")(3),
          blockName = "test3 (3)",
          ruleName = "groups_provider_authorization",
          message = "The rule 'groups_provider_authorization' will fail to match the impersonating request when the mock of the service 'grp1' is not configured",
          hint = "Configure a mock of an external authorization service with ID [grp1]"
        )
      }
      "return info that some of mocks are configured" in {
        rorApiManager
          .updateRorTestConfig(testEngineConfig())
          .forceOk()

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

        rorApiManager.configureImpersonationMocks(updateMocksPayload(payloadServices)).forceOk()

        val response = rorApiManager.currentMockedServices()
        response.responseCode should be(200)
        response.responseJson("status").str should be("TEST_SETTINGS_PRESENT")
        response.responseJson("services") should be(payloadServices)
      }
      "return info that all mocks are configured" in {
        rorApiManager
          .updateRorTestConfig(testEngineConfig())
          .forceOk()

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

        rorApiManager.configureImpersonationMocks(updateMocksPayload(payloadServices)).forceOk()

        val response = rorApiManager.currentMockedServices()
        response.responseCode should be(200)
        response.responseJson("status").str should be("TEST_SETTINGS_PRESENT")
        response.responseJson("services") should be(payloadServices)

        val testConfigResponse = rorApiManager.currentRorTestConfig
        testConfigResponse.responseJson("status").str should be("TEST_SETTINGS_PRESENT")
        testConfigResponse.responseJson("warnings").arr.size should be(1)

        assertTestConfigWarning(
          json = testConfigResponse.responseJson("warnings")(0),
          blockName = "test2 (1)",
          ruleName = "auth_key_sha1",
          message = "The rule contains fully hashed username and password. It doesn't support impersonation in this configuration",
          hint = "You can use second version of the rule and use not hashed username. Like that: `auth_key_sha1: USER_NAME:hash(PASSWORD)"
        )
      }
    }
    "provide a method for reload mocked services" which {
      "is going to reload mocked services" when {
        "configuration is correct" when {
          "all services are passed" in {
            rorApiManager
              .updateRorTestConfig(testEngineConfig())
              .forceOk()

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

            val response = rorApiManager.configureImpersonationMocks(updateMocksPayload(payloadServices))
            response.responseCode should be(200)
            response.responseJson("status").str should be("OK")
            response.responseJson("message").str should be("Auth mock updated")
          }
          "only some services are passed" in {
            rorApiManager
              .updateRorTestConfig(testEngineConfig())
              .forceOk()

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

            val response = rorApiManager.configureImpersonationMocks(updateMocksPayload(payloadServices))
            response.responseCode should be(200)
            response.responseJson("status").str should be("OK")
            response.responseJson("message").str should be("Auth mock updated")
          }
          "any of services are passed" in {
            rorApiManager
              .updateRorTestConfig(testEngineConfig())
              .forceOk()

            val response = rorApiManager.configureImpersonationMocks(ujson.read(
              s"""
                 |{
                 |  "services": []
                 |}
                 |""".stripMargin
            ))
            response.responseCode should be(200)
            response.responseJson("status").str should be("OK")
            response.responseJson("message").str should be("Auth mock updated")
          }
        }
      }
      "return info that test settings are invalidated" in {
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
        val response = rorApiManager.configureImpersonationMocks(updateMocksPayload(payloadServices))
        response.responseCode should be(200)
        response.responseJson("status").str should be("TEST_SETTINGS_INVALIDATED")
        response.responseJson("message").str should be("ROR Test settings are invalidated. To use Auth Services Mock ROR has to have Test settings active.")
      }
      "return info that unknown services detected" when {
        "unknown service passed" in {
          rorApiManager
            .updateRorTestConfig(testEngineConfig())
            .forceOk()

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

          val response = rorApiManager.configureImpersonationMocks(updateMocksPayload(payloadServices))
          response.responseCode should be(200)
          response.responseJson("status").str should be("UNKNOWN_AUTH_SERVICES_DETECTED")
          response.responseJson("message").str should be("ROR doesn't allow to configure unknown Auth Services. Only the ones used in ROR's Test settings can be configured. Unknown services: [ldap3,ldap4]")
        }
      }
      "return info that request is malformed" when {
        "unknown service type" in {
          rorApiManager
            .updateRorTestConfig(testEngineConfig())
            .forceOk()

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

          val response = rorApiManager.configureImpersonationMocks(updateMocksPayload(malformedPayload))
          response.responseCode should be(400)
          response.responseJson("status").str should be("FAILED")
          response.responseJson("message").str should be("JSON body malformed: [Could not parse at : [Unknown auth mock service type: EXT_AUTHORIZATION]]")

        }
        "empty JSON is passed" in {
          rorApiManager
            .updateRorTestConfig(testEngineConfig())
            .forceOk()

          val response = rorApiManager.configureImpersonationMocks("{}")
          response.responseCode should be(400)
          response.responseJson("status").str should be("FAILED")
          response.responseJson("message").str should be("JSON body malformed: [Could not parse at .services: [Attempt to decode value on failed cursor: DownField(services)]]")
        }
        "JSON with malformed ldap user objects" in {
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

          val response = rorApiManager.configureImpersonationMocks(updateMocksPayload(malformedPayload))
          response.responseCode should be(400)
          response.responseJson("status").str should be("FAILED")
          response.responseJson("message").str should be("JSON body malformed: [Could not parse at .services: [C[A]: DownField(services)]]")
        }
      }
    }
  }

  private def assertTestConfigWarning(json: BaseManager.JSON,
                                      blockName:String,
                                      ruleName: String,
                                      message: String,
                                      hint: String) = {
    List(
      json("block_name").str,
      json("rule_name").str,
      json("message").str,
      json("hint").str,
    ) should be(List(blockName, ruleName, message, hint))
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

  private def testEngineConfig() = resolvedRorConfigFile.contentAsString

  override protected def beforeEach(): Unit = {
    rorApiManager
      .invalidateImpersonationMocks()
      .force()

    rorApiManager
      .invalidateRorTestConfig()
      .forceOk()
  }

}
