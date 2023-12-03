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

import org.apache.commons.codec.binary.Base64
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyFreeSpecLike, SingletonLdapContainers, SingletonPluginTestSupport}
import tech.beshu.ror.utils.containers.dependencies.{ldap, wiremock}
import tech.beshu.ror.utils.containers.{DependencyDef, ElasticsearchNodeDataInitializer}
import tech.beshu.ror.utils.elasticsearch.BaseManager.SimpleHeader
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, RorApiManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers

class ImpersonationSuite
  extends AnyFreeSpec
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport
    with ESVersionSupportForAnyFreeSpecLike
    with BeforeAndAfterEach
    with BeforeAndAfterAll 
    with CustomScalaTestMatchers {

  override implicit val rorConfigFileName: String = "/impersonation/readonlyrest.yml"

  override def nodeDataInitializer: Option[ElasticsearchNodeDataInitializer] = Some(ImpersonationSuite.nodeDataInitializer())

  override def clusterDependencies: List[DependencyDef] = List(
    ldap(name = "LDAP1", SingletonLdapContainers.ldap1),
    ldap(name = "LDAP2", SingletonLdapContainers.ldap2),
    wiremock(name = "EXT1", mappings = "/impersonation/wiremock_service1_ext_user_1.json", "/impersonation/wiremock_group_provider1_gpa_user_1.json"),
    wiremock(name = "EXT1", mappings = "/impersonation/wiremock_service2_ext_user_2.json", "/impersonation/wiremock_group_provider2_gpa_user_2.json"),
  )

  private lazy val rorApiManager = new RorApiManager(adminClient, esVersionUsed)

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    loadTestSettings()
    rorApiManager
      .updateRorInIndexConfig( // In a test, the main engine config should be different from the test config to prevent accidental use of the main engine
        s"""
           |readonlyrest:
           |  access_control_rules:
           |    # ES containter initializer need this rule to configure ES instance after startup
           |    - name: "CONTAINER ADMIN"
           |      verbosity: error
           |      type: allow
           |      auth_key: admin:container
           |""".stripMargin
      )
      .forceOkStatus()
  }

  override protected def beforeEach(): Unit = {
    rorApiManager.invalidateImpersonationMocks().force()
    super.beforeEach()
  }

  "Impersonation for" - {
    "'auth_key' rule" - {
      "is supported and" - {
        "impersonator can be properly authenticated" in {
          impersonatingSearchManagers("admin1", "pass", impersonatedUser = "dev1").foreach { searchManager =>
            val result = searchManager.search("test1_index")

            result should have statusCode 200
          }
        }
      }
      "is not supported when rule uses full hashed auth credentials" in {
        impersonatingSearchManagers("admin1", "pass", impersonatedUser = "dev1").foreach { searchManager =>
          val result = searchManager.search("test2_index")

          result should have statusCode 401
          result.responseJson should be(impersonationNotSupportedResponse)
          result.headers should contain(SimpleHeader("WWW-Authenticate", "Basic"))
        }
      }
    }
    "'proxy_auth' rule" - {
      "is supported and" - {
        "impersonator can be properly authenticated" in {
          impersonatingSearchManagers("admin1", "pass", impersonatedUser = "proxy_user_1").foreach { searchManager =>
            val result = searchManager.search("test2_index")

            result should have statusCode 200
          }
        }
      }
    }
    "'ldap_auth' rule" - {
      "is not supported" - {
        "by default" in {
          impersonatingSearchManagers("admin1", "pass", impersonatedUser = "ldap_user_1").foreach { searchManager =>
            val result = searchManager.search("test3_index")

            result should have statusCode 401
            result.responseJson should be(impersonationNotSupportedResponse)
          }
        }
        "when ldap service used in rule is not mocked" in {
          rorApiManager
            .configureImpersonationMocks(ujson.read(
              s"""
                 |{
                 |  "services": [
                 |    {
                 |      "type": "LDAP",
                 |      "name": "ldap2",
                 |      "mock": {
                 |        "users" : [
                 |          {
                 |            "name": "ldap_user_2",
                 |            "groups": ["group1", "group3"]
                 |          }
                 |        ]
                 |      }
                 |    }
                 |  ]
                 |}
             """.stripMargin
            ))
            .forceOkStatus()

          impersonatingSearchManagers("admin1", "pass", impersonatedUser = "ldap_user_1").foreach { searchManager =>
            val result = searchManager.search("test3_index")

            result should have statusCode 401
            result.responseJson should be(impersonationNotSupportedResponse)
          }
        }
      }
      "is supported" - {
        "when ldap service used in rule is mocked" in {
          rorApiManager
            .configureImpersonationMocks(ujson.read(
              s"""
                 |{
                 |  "services": [
                 |    {
                 |      "type": "LDAP",
                 |      "name": "ldap1",
                 |      "mock": {
                 |        "users" : [
                 |          {
                 |            "name": "ldap_user_1",
                 |            "groups": ["group1", "group2"]
                 |          }
                 |        ]
                 |      }
                 |    }
                 |  ]
                 |}
                 |""".stripMargin
            ))
            .forceOkStatus()

          impersonatingSearchManagers("admin1", "pass", impersonatedUser = "ldap_user_1").foreach { searchManager =>
            val result = searchManager.search("test3_index")

            result should have statusCode 200
          }
        }
      }
    }
    "'external_authentication' rule" - {
      "is not supported" - {
        "by default" in {
          impersonatingSearchManagers("admin1", "pass", impersonatedUser = "ext_user_1").foreach { searchManager =>
            val result = searchManager.search("test3_index")

            result should have statusCode 401
            result.responseJson should be(impersonationNotSupportedResponse)
          }
        }
        "when external auth service used in rule is not mocked" in {
          rorApiManager
            .configureImpersonationMocks(ujson.read(
              s"""
                 |{
                 |  "services": [
                 |    {
                 |      "type": "EXT_AUTHN",
                 |      "name": "ext2",
                 |      "mock": {
                 |        "users" : [
                 |          { "name": "ext_user_2" },
                 |          { "name": "ext_user_2a" }
                 |        ]
                 |      }
                 |    }
                 |  ]
                 |}
                 |""".stripMargin
            ))
            .forceOkStatus()

          impersonatingSearchManagers("admin1", "pass", impersonatedUser = "ext_user_1").foreach { searchManager =>
            val result = searchManager.search("test3_index")

            result should have statusCode 401
            result.responseJson should be(impersonationNotSupportedResponse)
          }
        }
      }
      "is supported" - {
        "when external auth service used in rule is mocked" in {
          rorApiManager
            .configureImpersonationMocks(ujson.read(
              s"""
                 |{
                 |  "services": [
                 |    {
                 |      "type": "EXT_AUTHN",
                 |      "name": "ext1",
                 |      "mock": {
                 |        "users": [
                 |          { "name": "ext_user_1" },
                 |          { "name": "ext_user_2" }
                 |        ]
                 |      }
                 |    }
                 |  ]
                 |}
                 |""".stripMargin
            ))
            .forceOkStatus()

          impersonatingSearchManagers("admin1", "pass", impersonatedUser = "ext_user_1").foreach { searchManager =>
            val result = searchManager.search("test3_index")

            result should have statusCode 200
          }
        }
      }
    }
    "'external_authorization' rule" - {
      "is not supported" - {
        "by default" in {
          impersonatingSearchManagers("admin1", "pass", impersonatedUser = "gpa_user_1").foreach { searchManager =>
            val result = searchManager.search("test3_index")

            result should have statusCode 401
            result.responseJson should be(impersonationNotSupportedResponse)
          }
        }
        "when external auth service used in rule is not mocked" in {
          rorApiManager
            .configureImpersonationMocks(ujson.read(
              s"""
                 |{
                 |  "services": [
                 |    {
                 |      "type": "EXT_AUTHZ",
                 |      "name": "grp2",
                 |      "mock": {
                 |        "users" : [
                 |          { "name": "gpa_user_1",  "groups": ["group4", "group5"]},
                 |          { "name": "gpa_user_1a", "groups": ["group4a", "group5a"] }
                 |        ]
                 |      }
                 |    }
                 |  ]
                 |}
                 |""".stripMargin
            ))
            .forceOkStatus()

          impersonatingSearchManagers("admin1", "pass", impersonatedUser = "gpa_user_1").foreach { searchManager =>
            val result = searchManager.search("test3_index")

            result should have statusCode 401
            result.responseJson should be(impersonationNotSupportedResponse)
          }
        }
      }
      "is supported" - {
        "when external auth service used in rule is mocked" in {
          rorApiManager
            .configureImpersonationMocks(ujson.read(
              s"""
                 |{
                 |  "services": [
                 |    {
                 |      "type": "EXT_AUTHZ",
                 |      "name": "grp1",
                 |      "mock": {
                 |        "users" : [
                 |          { "name": "gpa_user_1",  "groups": ["group4", "group5"]},
                 |          { "name": "gpa_user_1a", "groups": ["group4a", "group5a"] }
                 |        ]
                 |      }
                 |    }
                 |  ]
                 |}
                 |""".stripMargin
            ))
            .forceOkStatus()

          impersonatingSearchManagers("admin1", "pass", impersonatedUser = "gpa_user_1").foreach { searchManager =>
            val result = searchManager.search("test3_index")

            result should have statusCode 200
          }
        }
      }
    }
    "'groups' rule" - {
      "is not supported" - {
        "when ldap service used in internal auth rule is not mocked" in {
          rorApiManager
            .configureImpersonationMocks(ujson.read(
              s"""
                 |{
                 |  "services": [
                 |    {
                 |      "type": "LDAP",
                 |      "name": "ldap2",
                 |      "mock": {
                 |        "users" : [
                 |          {
                 |            "name": "ldap_user_2",
                 |            "groups": ["group1", "group3"]
                 |          }
                 |        ]
                 |      }
                 |    }
                 |  ]
                 |}
                 |""".stripMargin
            ))
            .forceOkStatus()

          impersonatingSearchManagers("admin1", "pass", impersonatedUser = "ldap_user_1").foreach { searchManager =>
            val result = searchManager.search("test4_index")

            result should have statusCode 401
            result.responseJson should be(impersonationNotSupportedResponse)
          }
        }
      }
      "is supported" - {
        "by default when internal auth rule with " in {
          impersonatingSearchManagers("admin1", "pass", impersonatedUser = "dev2").foreach { searchManager =>
            val result = searchManager.search("test4_index")

            result should have statusCode 200
          }
        }
        "when ldap service used in internal auth rule is mocked" in {
          rorApiManager
            .configureImpersonationMocks(ujson.read(
              s"""
                 |{
                 |  "services": [
                 |    {
                 |      "type": "LDAP",
                 |      "name": "ldap1",
                 |      "mock": {
                 |        "users" : [
                 |          {
                 |            "name": "ldap_user_1",
                 |            "groups": ["group1", "group2"]
                 |          }
                 |        ]
                 |      }
                 |    }
                 |  ]
                 |}
                 |""".stripMargin
            ))
            .forceOkStatus()

          impersonatingSearchManagers("admin1", "pass", impersonatedUser = "ldap_user_1").foreach { searchManager =>
            val result = searchManager.search("test4_index")

            result should have statusCode 200
          }
        }
      }
    }
  }

  "Impersonation cannot be done when" - {
    "there is no such user with admin privileges" in {
      impersonatingSearchManagers("unknown", "pass", impersonatedUser = "dev1").foreach { searchManager =>
        val result = searchManager.search("test1_index")

        result should have statusCode 401
        result.responseJson should be(impersonationNotAllowedResponse)
        result.headers should contain(SimpleHeader("WWW-Authenticate", "Basic"))
      }
    }
    "user with admin privileges cannot be authenticated" in {
      impersonatingSearchManagers("admin1", "wrong_pass", impersonatedUser = "dev1").foreach { searchManager =>
        val result = searchManager.search("test1_index")

        result should have statusCode 401
        result.responseJson should be(impersonationNotAllowedResponse)
        result.headers should contain(SimpleHeader("WWW-Authenticate", "Basic"))
      }
    }
    "admin user is authenticated but cannot impersonate given user" in {
      impersonatingSearchManagers("admin2", "pass", impersonatedUser = "dev1").foreach { searchManager =>
        val result = searchManager.search("test1_index")

        result should have statusCode 401
        result.responseJson should be(impersonationNotAllowedResponse)
        result.headers should contain(SimpleHeader("WWW-Authenticate", "Basic"))
      }
    }
    "mocks were invalidated" in {
      impersonatingSearchManagers("admin1", "pass", impersonatedUser = "ldap_user_1").foreach { searchManager =>
        rorApiManager
          .configureImpersonationMocks(ujson.read(
            s"""
               |{
               |  "services": [
               |    {
               |      "type": "LDAP",
               |      "name": "ldap1",
               |      "mock": {
               |        "users" : [
               |          {
               |            "name": "ldap_user_1",
               |            "groups": ["group1", "group2"]
               |          }
               |        ]
               |      }
               |    }
               |  ]
               |}
               |""".stripMargin
          ))
          .forceOkStatus()

        val result1 = searchManager.search("test3_index")

        result1 should have statusCode 200

        rorApiManager.invalidateImpersonationMocks().forceOkStatus()

        val result2 = searchManager.search("test3_index")

        result2 should have statusCode 401
        result2.responseJson should be(impersonationNotSupportedResponse)
      }
    }
    "test engine is not configured" in {
      impersonatingSearchManagers("admin1", "pass", impersonatedUser = "ldap_user_1").foreach { searchManager =>
        rorApiManager.invalidateRorTestConfig().forceOkStatus()
        loadTestSettings()

        rorApiManager
          .configureImpersonationMocks(ujson.read(
            s"""
               |{
               |  "services": [
               |    {
               |      "type": "LDAP",
               |      "name": "ldap1",
               |      "mock": {
               |        "users" : [
               |          {
               |            "name": "ldap_user_1",
               |            "groups": ["group1", "group2"]
               |          }
               |        ]
               |      }
               |    }
               |  ]
               |}
               |""".stripMargin
          ))
          .forceOkStatus()

        val result1 = searchManager.search("test3_index")
        result1 should have statusCode 200

        rorApiManager.invalidateRorTestConfig().forceOkStatus()

        val result2 = searchManager.search("test3_index")
        result2 should have statusCode 403
        result2.responseJson should be(testSettingsNotConfiguredResponse)
      }
    }
  }

  "Current user metadata request should support impersonation and" - {
    "return 200 when the user can be impersonated" in {
      loadTestSettings()
      configureSomeMocksForAllExternalServices()

      impersonatingRorApiManagers("admin1", "pass", impersonatedUser = "dev1").foreach { apiManger =>
        val result = apiManger.fetchMetadata()

        result should have statusCode 200
      }
    }
    "return 401 and IMPERSONATION_NOT_ALLOWED when the impersonator cannot be authenticated" in {
      loadTestSettings()
      configureSomeMocksForAllExternalServices()

      impersonatingRorApiManagers("admin1", "wrong_password", impersonatedUser = "dev1").foreach { apiManger =>
        val result = apiManger.fetchMetadata()

        result should have statusCode 401
        result.responseJson("error")("due_to").arr.map(_.str).toSet should be(Set("OPERATION_NOT_ALLOWED", "IMPERSONATION_NOT_ALLOWED"))
      }
    }
    "return 401 and IMPERSONATION_NOT_ALLOWED when the impersonator is not allowed to impersonate a given user" in {
      loadTestSettings()
      configureSomeMocksForAllExternalServices()

      impersonatingRorApiManagers("admin2", "pass", impersonatedUser = "dev1").foreach { apiManger =>
        val result = apiManger.fetchMetadata()

        result should have statusCode 401
        result.responseJson("error")("due_to").arr.map(_.str).toSet should be(Set("OPERATION_NOT_ALLOWED", "IMPERSONATION_NOT_ALLOWED"))
      }
    }
    "return 401 and IMPERSONATION_NOT_SUPPORTED when there is no matched block and at least one don't support impersonation" in {
      loadTestSettings()
      configureSomeMocksForAllExternalServices()

      impersonatingRorApiManagers("admin1", "pass", impersonatedUser = "dev3").foreach { apiManger =>
        val result = apiManger.fetchMetadata()

        result should have statusCode 401
        result.responseJson("error")("due_to").arr.map(_.str).toSet should be(Set("OPERATION_NOT_ALLOWED", "IMPERSONATION_NOT_SUPPORTED"))
      }
    }
  }

  private def impersonatingSearchManagers(user: String, pass: String, impersonatedUser: String) = {
    impersonatingManager(
      user, pass, impersonatedUser,
      (client, _, additionalHeaders) => new SearchManager(client, additionalHeaders)
    )
  }

  private def impersonatingRorApiManagers(user: String, pass: String, impersonatedUser: String) = {
    impersonatingManager(
      user, pass, impersonatedUser,
      (client, esVersion, additionalHeaders) => new RorApiManager(client, esVersion, additionalHeaders)
    )
  }

  private def impersonatingManager[T](user: String,
                                      pass: String,
                                      impersonatedUser: String,
                                      managerCreator: (RestClient, String, Map[String, String]) => T) = {
    List(
      managerCreator(
        basicAuthClient(user, pass),
        esVersionUsed,
        Map("x-ror-impersonating" -> impersonatedUser)
      ),
      managerCreator(
        noBasicAuthClient,
        esVersionUsed,
        Map(authorizationHeaderWithRorMetadata((user, pass), Map("x-ror-impersonating" -> impersonatedUser)))
      )
    )
  }

  private def authorizationHeaderWithRorMetadata(userCredentials: (String, String),
                                                 headersToEncode: Map[String, String]) = {
    def encodeBase64(value: String): String =
      Base64.encodeBase64(value.getBytes, false).map(_.toChar).mkString

    val rorMetadata =
      s"""
         |{
         |  "headers": [
         |    ${headersToEncode.map { case (name, value) => s""""$name:$value"""" }.mkString(",\n")}
         |  ]
         |}
         |""".stripMargin
    val (user, pass) = userCredentials
    "Authorization" -> s"Basic ${encodeBase64(s"$user:$pass")}, ror_metadata=${encodeBase64(rorMetadata)}"
  }

  private def loadTestSettings(): Unit = {
    rorApiManager
      .updateRorTestConfig(resolvedRorConfigFile.contentAsString)
      .forceOkStatus()
  }

  private def configureSomeMocksForAllExternalServices(): Unit = {
    rorApiManager
      .configureImpersonationMocks(ujson.read(
        s"""
           |{
           |  "services": [
           |    {
           |      "type": "LDAP",
           |      "name": "ldap1",
           |      "mock": {
           |        "users" : [
           |          {
           |            "name": "ldap_user_1",
           |            "groups": ["group1", "group2"]
           |          }
           |        ]
           |      }
           |    },
           |    {
           |      "type": "LDAP",
           |      "name": "ldap2",
           |      "mock": {
           |        "users" : [
           |          {
           |            "name": "ldap_user_2",
           |            "groups": ["group1", "group2"]
           |          }
           |        ]
           |      }
           |    },
           |    {
           |      "type": "EXT_AUTHN",
           |      "name": "ext1",
           |      "mock": {
           |        "users": [
           |          { "name": "ext_user_1" }
           |        ]
           |      }
           |    },
           |    {
           |      "type": "EXT_AUTHN",
           |      "name": "ext2",
           |      "mock": {
           |        "users": [
           |          { "name": "ext_user_2" }
           |        ]
           |      }
           |    },
           |    {
           |      "type": "EXT_AUTHZ",
           |      "name": "grp1",
           |      "mock": {
           |        "users" : [
           |          { "name": "gpa_user_1",  "groups": ["group4", "group5"]},
           |          { "name": "gpa_user_1a", "groups": ["group4a", "group5a"] }
           |        ]
           |      }
           |    },
           |    {
           |      "type": "EXT_AUTHZ",
           |      "name": "grp2",
           |      "mock": {
           |        "users" : [
           |          { "name": "gpa_user_2",  "groups": ["group4", "group5"]},
           |          { "name": "gpa_user_2a", "groups": ["group4a", "group5a"] }
           |        ]
           |      }
           |    }
           |  ]
           |}
           |""".stripMargin
      ))
      .forceOkStatus()
  }

  private lazy val impersonationNotSupportedResponse = ujson.read(
    """
      |{
      |  "error":{
      |    "root_cause":[
      |      {
      |        "type":"forbidden_response",
      |        "reason":"forbidden",
      |        "due_to":["OPERATION_NOT_ALLOWED", "IMPERSONATION_NOT_SUPPORTED"],
      |        "header":{"WWW-Authenticate":"Basic"}
      |      }
      |    ],
      |    "type":"forbidden_response",
      |    "reason":"forbidden",
      |    "due_to":["OPERATION_NOT_ALLOWED", "IMPERSONATION_NOT_SUPPORTED"],
      |    "header":{"WWW-Authenticate":"Basic"}
      |  },
      |  "status":401
      |}
    """.stripMargin)

  private lazy val impersonationNotAllowedResponse = ujson.read(
    """
      |{
      |  "error":{
      |    "root_cause":[
      |      {
      |        "type":"forbidden_response",
      |        "reason":"forbidden",
      |        "due_to":["OPERATION_NOT_ALLOWED", "IMPERSONATION_NOT_ALLOWED"],
      |        "header":{"WWW-Authenticate":"Basic"}
      |      }
      |    ],
      |    "type":"forbidden_response",
      |    "reason":"forbidden",
      |    "due_to":["OPERATION_NOT_ALLOWED", "IMPERSONATION_NOT_ALLOWED"],
      |    "header":{"WWW-Authenticate":"Basic"}
      |  },
      |  "status":401
      |}
    """.stripMargin)

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

object ImpersonationSuite {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion: String, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    documentManager.createDoc("test1_index", 1, ujson.read("""{"hello":"world"}""")).force()
    documentManager.createDoc("test2_index", 1, ujson.read("""{"hello":"world"}""")).force()
    documentManager.createDoc("test3_index", 1, ujson.read("""{"hello":"world"}""")).force()
    documentManager.createDoc("test4_index", 1, ujson.read("""{"hello":"world"}""")).force()
  }
}