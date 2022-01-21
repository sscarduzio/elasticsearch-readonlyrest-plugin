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
import org.scalatest.freespec.AnyFreeSpec
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyFreeSpecLike, SingletonLdapContainers}
import tech.beshu.ror.utils.containers.dependencies.{ldap, wiremock}
import tech.beshu.ror.utils.containers.{DependencyDef, ElasticsearchNodeDataInitializer, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.BaseManager.SimpleHeader
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, RorApiManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait ImpersonationSuite
  extends AnyFreeSpec
    with BaseSingleNodeEsClusterTest
    with ESVersionSupportForAnyFreeSpecLike
    with BeforeAndAfterEach {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/impersonation/readonlyrest.yml"

  override def nodeDataInitializer: Option[ElasticsearchNodeDataInitializer] = Some(ImpersonationSuite.nodeDataInitializer())

  override def clusterDependencies: List[DependencyDef] = List(
    ldap(name = "LDAP1", SingletonLdapContainers.ldap1),
    ldap(name = "LDAP2", SingletonLdapContainers.ldap2),
    wiremock(name = "EXT1", mappings = "/impersonation/wiremock_service1_ext_user_1.json", "/impersonation/wiremock_group_provider1_gpa_user_1.json"),
    wiremock(name = "EXT1", mappings = "/impersonation/wiremock_service2_ext_user_2.json", "/impersonation/wiremock_group_provider2_gpa_user_2.json"),
  )

  private lazy val rorApiManager = new RorApiManager(rorAdminClient, esVersionUsed)

  override protected def beforeEach(): Unit = {
    rorApiManager.invalidateImpersonationMocks().forceOk()
    super.beforeEach()
  }

  "Impersonation for" - {
    "'auth_key' rule" - {
      "is supported and" - {
        "impersonator can be properly authenticated" in {
          val searchManager = new SearchManager(
            basicAuthClient("admin1", "pass"),
            Map("impersonate_as" -> "dev1")
          )

          val result = searchManager.search("test1_index")

          result.responseCode should be(200)
        }
      }
      "is not supported when rule uses full hashed auth credentials" in {
        val searchManager = new SearchManager(
          basicAuthClient("admin1", "pass"),
          Map("impersonate_as" -> "dev1")
        )

        val result = searchManager.search("test2_index")

        result.responseCode should be(401)
        result.responseJson should be(impersonationNotSupportedResponse)
        result.headers should contain(SimpleHeader("WWW-Authenticate", "Basic"))
      }
    }
    "'proxy_auth' rule" - {
      "is supported and" - {
        "impersonator can be properly authenticated" in {
          val searchManager = new SearchManager(
            basicAuthClient("admin1", "pass"),
            Map("impersonate_as" -> "proxy_user_1")
          )

          val result = searchManager.search("test2_index")

          result.responseCode should be(200)
        }
      }
    }
    "'ldap_auth' rule" - {
      "is not supported" - {
        "by default" in {
          val searchManager = new SearchManager(
            basicAuthClient("admin1", "pass"),
            Map("impersonate_as" -> "ldap_user_1")
          )

          val result = searchManager.search("test3_index")

          result.responseCode should be(401)
          result.responseJson should be(impersonationNotSupportedResponse)
        }
        "when ldap service used in rule is not mocked" in {
          rorApiManager
            .configureImpersonationMocks(ujson.read(
              s"""
                 |{
                 |  "ldaps": {
                 |    "ldap2": {
                 |      "users": [
                 |        {
                 |          "name": "ldap_user_2",
                 |          "groups": ["group1", "group3"]
                 |        }
                 |      ]
                 |    }
                 |  }
                 |}
             """.stripMargin
            ))
            .forceOk()

          val searchManager = new SearchManager(
            basicAuthClient("admin1", "pass"),
            Map("impersonate_as" -> "ldap_user_1")
          )

          val result = searchManager.search("test3_index")

          result.responseCode should be(401)
          result.responseJson should be(impersonationNotSupportedResponse)
        }
      }
      "is supported" - {
        "when ldap service used in rule is mocked" in {
          rorApiManager
            .configureImpersonationMocks(ujson.read(
              s"""
                 |{
                 |  "ldaps": {
                 |    "ldap1": {
                 |      "users": [
                 |        {
                 |          "name": "ldap_user_1",
                 |          "groups": ["group1", "group2"]
                 |        }
                 |      ]
                 |    }
                 |  }
                 |}
             """.stripMargin
            ))
            .forceOk()

          val searchManager = new SearchManager(
            basicAuthClient("admin1", "pass"),
            Map("impersonate_as" -> "ldap_user_1")
          )

          val result = searchManager.search("test3_index")

          result.responseCode should be(200)
        }
      }
    }
    "'external_authentication' rule" - {
      "is not supported" - {
        "by default" in {
          val searchManager = new SearchManager(
            basicAuthClient("admin1", "pass"),
            Map("impersonate_as" -> "ext_user_1")
          )

          val result = searchManager.search("test3_index")

          result.responseCode should be(401)
          result.responseJson should be(impersonationNotSupportedResponse)
        }
        "when external auth service used in rule is not mocked" in {
          rorApiManager
            .configureImpersonationMocks(ujson.read(
              s"""
                 |{
                 |  "authn_services": {
                 |    "ext2": {
                 |      "users": [
                 |        { "name": "ext_user_2" },
                 |        { "name": "ext_user_2a" }
                 |      ]
                 |    }
                 |  }
                 |}
             """.stripMargin
            ))
            .forceOk()

          val searchManager = new SearchManager(
            basicAuthClient("admin1", "pass"),
            Map("impersonate_as" -> "ext_user_1")
          )

          val result = searchManager.search("test3_index")

          result.responseCode should be(401)
          result.responseJson should be(impersonationNotSupportedResponse)
        }
      }
      "is supported" - {
        "when external auth service used in rule is mocked" in {
          rorApiManager
            .configureImpersonationMocks(ujson.read(
              s"""
                 |{
                 |  "authn_services": {
                 |    "ext1": {
                 |      "users": [
                 |        { "name": "ext_user_1" },
                 |        { "name": "ext_user_2" }
                 |      ]
                 |    }
                 |  }
                 |}
             """.stripMargin
            ))
            .forceOk()

          val searchManager = new SearchManager(
            basicAuthClient("admin1", "pass"),
            Map("impersonate_as" -> "ext_user_1")
          )

          val result = searchManager.search("test3_index")

          result.responseCode should be(200)
        }
      }
    }
    "'external_authorization' rule" - {
      "is not supported" - {
        "by default" in {
          val searchManager = new SearchManager(
            basicAuthClient("admin1", "pass"),
            Map("impersonate_as" -> "gpa_user_1")
          )

          val result = searchManager.search("test3_index")

          result.responseCode should be(401)
          result.responseJson should be(impersonationNotSupportedResponse)
        }
        "when external auth service used in rule is not mocked" in {
          rorApiManager
            .configureImpersonationMocks(ujson.read(
              s"""
                 |{
                 |  "authz_services": {
                 |    "grp2": {
                 |      "users": [
                 |        { "name": "gpa_user_1",  "groups": ["group4", "group5"]},
                 |        { "name": "gpa_user_1a", "groups": ["group4a", "group5a"] }
                 |      ]
                 |    }
                 |  }
                 |}
             """.stripMargin
            ))
            .forceOk()

          val searchManager = new SearchManager(
            basicAuthClient("admin1", "pass"),
            Map("impersonate_as" -> "gpa_user_1")
          )

          val result = searchManager.search("test3_index")

          result.responseCode should be(401)
          result.responseJson should be(impersonationNotSupportedResponse)
        }
      }
      "is supported" - {
        "when external auth service used in rule is mocked" in {
          rorApiManager
            .configureImpersonationMocks(ujson.read(
              s"""
                 |{
                 |  "authz_services": {
                 |    "grp1": {
                 |      "users": [
                 |        { "name": "gpa_user_1",  "groups": ["group4", "group5"]},
                 |        { "name": "gpa_user_1a", "groups": ["group4a", "group5a"] }
                 |      ]
                 |    }
                 |  }
                 |}
             """.stripMargin
            ))
            .forceOk()

          val searchManager = new SearchManager(
            basicAuthClient("admin1", "pass"),
            Map("impersonate_as" -> "gpa_user_1")
          )

          val result = searchManager.search("test3_index")

          result.responseCode should be(200)
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
                 |  "ldaps": {
                 |    "ldap2": {
                 |      "users": [
                 |        {
                 |          "name": "ldap_user_2",
                 |          "groups": ["group1", "group3"]
                 |        }
                 |      ]
                 |    }
                 |  }
                 |}
             """.stripMargin
            ))
            .forceOk()

          val searchManager = new SearchManager(
            basicAuthClient("admin1", "pass"),
            Map("impersonate_as" -> "ldap_user_1")
          )

          val result = searchManager.search("test4_index")

          result.responseCode should be(401)
          result.responseJson should be(impersonationNotSupportedResponse)
        }
      }
      "is supported" - {
        "by default when internal auth rule with " in {
          val searchManager = new SearchManager(
            basicAuthClient("admin1", "pass"),
            Map("impersonate_as" -> "dev2")
          )

          val result = searchManager.search("test4_index")

          result.responseCode should be(200)
        }
        "when ldap service used in internal auth rule is mocked" in {
          rorApiManager
            .configureImpersonationMocks(ujson.read(
              s"""
                 |{
                 |  "ldaps": {
                 |    "ldap1": {
                 |      "users": [
                 |        {
                 |          "name": "ldap_user_1",
                 |          "groups": ["group1", "group2"]
                 |        }
                 |      ]
                 |    }
                 |  }
                 |}
             """.stripMargin
            ))
            .forceOk()

          val searchManager = new SearchManager(
            basicAuthClient("admin1", "pass"),
            Map("impersonate_as" -> "ldap_user_1")
          )

          val result = searchManager.search("test4_index")

          result.responseCode should be(200)
        }
      }
    }
  }

  "Impersonation cannot be done when" - {
    "there is no such user with admin privileges" in {
      val searchManager = new SearchManager(
        basicAuthClient("unknown", "pass"),
        Map("impersonate_as" -> "dev1")
      )

      val result = searchManager.search("test1_index")

      result.responseCode should be(401)
      result.responseJson should be(impersonationNotAllowedResponse)
      result.headers should contain(SimpleHeader("WWW-Authenticate", "Basic"))
    }
    "user with admin privileges cannot be authenticated" in {
      val searchManager = new SearchManager(
        basicAuthClient("admin1", "wrong_pass"),
        Map("impersonate_as" -> "dev1")
      )

      val result = searchManager.search("test1_index")

      result.responseCode should be(401)
      result.responseJson should be(impersonationNotAllowedResponse)
      result.headers should contain(SimpleHeader("WWW-Authenticate", "Basic"))
    }
    "admin user is authenticated but cannot impersonate given user" in {
      val searchManager = new SearchManager(
        basicAuthClient("admin2", "pass"),
        Map("impersonate_as" -> "dev1")
      )

      val result = searchManager.search("test1_index")

      result.responseCode should be(401)
      result.responseJson should be(impersonationNotAllowedResponse)
      result.headers should contain(SimpleHeader("WWW-Authenticate", "Basic"))
    }
    "mocks were invalidated" in {
      rorApiManager
        .configureImpersonationMocks(ujson.read(
          s"""
             |{
             |  "ldaps": {
             |    "ldap1": {
             |      "users": [
             |        {
             |          "name": "ldap_user_1",
             |          "groups": ["group1", "group3"]
             |        }
             |      ]
             |    }
             |  }
             |}
             """.stripMargin
        ))
        .forceOk()

      val searchManager = new SearchManager(
        basicAuthClient("admin1", "pass"),
        Map("impersonate_as" -> "ldap_user_1")
      )

      val result1 = searchManager.search("test3_index")

      result1.responseCode should be(200)

      rorApiManager.invalidateImpersonationMocks().forceOk()

      val result2 = searchManager.search("test3_index")

      result2.responseCode should be(401)
      result2.responseJson should be(impersonationNotSupportedResponse)
    }
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