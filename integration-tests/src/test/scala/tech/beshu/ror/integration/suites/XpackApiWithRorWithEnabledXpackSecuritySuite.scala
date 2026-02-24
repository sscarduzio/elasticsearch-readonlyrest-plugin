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

import tech.beshu.ror.integration.suites.base.BaseXpackApiSuite
import tech.beshu.ror.utils.TestUjson.ujson
import tech.beshu.ror.utils.containers.SecurityType
import tech.beshu.ror.utils.containers.images.ReadonlyRestWithEnabledXpackSecurityPlugin
import tech.beshu.ror.utils.containers.images.domain.Enabled
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, SearchManager}
import tech.beshu.ror.utils.misc.Version

import java.util.Base64

class XpackApiWithRorWithEnabledXpackSecuritySuite extends BaseXpackApiSuite {

  override implicit val rorSettingsFileName: String = "/xpack_api/readonlyrest_without_ror_ssl.yml"

  override protected def rorClusterSecurityType: SecurityType =
    SecurityType.RorWithXpackSecurity(ReadonlyRestWithEnabledXpackSecurityPlugin.Config.Attributes.default.copy(
      rorSettingsFileName = rorSettingsFileName,
      restSsl = Enabled.Yes(ReadonlyRestWithEnabledXpackSecurityPlugin.Config.RestSsl.Xpack),
      internodeSsl = Enabled.Yes(ReadonlyRestWithEnabledXpackSecurityPlugin.Config.InternodeSsl.Xpack)
    ))

  "Security API" when {
    "/_security/user/_has_privileges endpoint is called" should {
      "return ROR artificial user" excludeES allEs6x in {
        val response = adminXpackApiManager.hasPrivileges(
          clusterPrivileges = "monitor" :: Nil,
          applicationPrivileges = ujson.read(
            s"""
               |{
               |  "application": "kibana",
               |  "resources":["space:default"],
               |  "privileges":["version:$esVersionUsed","login:"]
               |}
               |""".stripMargin
          ) :: Nil,
          indexPrivileges = ujson.read(
            s"""
               |{
               |  "names": [".monitoring-*-6-*,.monitoring-*-7-*"],
               |  "privileges":["read"]
               |}
               |""".stripMargin
          ) :: Nil
        )

        response should have statusCode 200
        response.responseJson should be(ujson.read(
          s"""
             |{
             |  "username":"_xpack",
             |  "has_all_requested":true,
             |  "cluster":{
             |    "monitor":true
             |  },
             |  "index":{
             |    ".monitoring-*-6-*,.monitoring-*-7-*":{
             |      "read":true
             |    }
             |  },
             |  "application":{
             |    "kibana":{
             |      "space:default":{
             |        "login:":true,
             |        "version:$esVersionUsed":true
             |      }
             |    }
             |  }
             |}
             |""".stripMargin
        ))
      }
    }
    "/_security/user/_privileges endpoint is called" should {
      "return ROR artificial user's privileges" excludeES allEs6x in {
        val response = adminXpackApiManager.userPrivileges()
        response should have statusCode 200
        if (Version.greaterOrEqualThan(esVersionUsed, 8, 3, 0)) {
          response.responseJson should be(ujson.read(
            s"""
               |{
               |  "cluster":["all"],
               |  "global":[],
               |  "indices":[
               |    {
               |      "names":["*"],
               |      "privileges":["all"],
               |      "allow_restricted_indices":false
               |    }
               |  ],
               |  "applications":[],
               |  "run_as":[]
               |}
               |""".stripMargin
          ))
        } else {
          response.responseJson should be(ujson.read(
            s"""
               |{
               |  "cluster":["all"],
               |  "global":[],
               |  "indices":[
               |    {
               |      "names":["*"],
               |      "privileges":["all"],
               |      "allow_restricted_indices":false
               |    }
               |  ],
               |  "applications":[
               |    {
               |      "application":"*",
               |      "privileges":["*"],
               |      "resources":["*"]
               |    }
               |  ],
               |  "run_as":[]
               |}
               |""".stripMargin
          ))
        }
      }
    }
    "API key grant request is called" should {
      "be allowed" excludeES(allEs6x, allEs7xBelowEs77x) in {
        val response = adminXpackApiManager.grantApiKeyPrivilege("admin", "admin")
        response should have statusCode 200
      }
    }
    "/_security/api_key endpoints are called" should {
      "allow getting an API key by id" excludeES allEs6x in {
        val createResponse = adminXpackApiManager.createApiKey("test-get-key")
        createResponse should have statusCode 200
        val apiKeyId = createResponse.responseJson("id").str

        val getResponse = adminXpackApiManager.getApiKey(apiKeyId)
        getResponse should have statusCode 200
        getResponse.responseJson("api_keys").arr.size should be(1)
        getResponse.responseJson("api_keys")(0)("id").str should be(apiKeyId)
        getResponse.responseJson("api_keys")(0)("name").str should be("test-get-key")
      }
      "allow creating and using an API key" excludeES(allEs6x, allEs7xBelowEs714x) in {
        val documentManager = new DocumentManager(adminClient, esVersionUsed)
        documentManager.createDoc(".apm-agent-configuration", 1, ujson.read("""{}""")).force()

        val createResponse = adminXpackApiManager.createApiKey("test-agent-key")
        createResponse should have statusCode 200
        createResponse.responseJson("name").str should be("test-agent-key")

        val apiKeyId = createResponse.responseJson("id").str
        val apiKeyValue = createResponse.responseJson("api_key").str
        val encodedKey = Base64.getEncoder.encodeToString(s"$apiKeyId:$apiKeyValue".getBytes)

        val agentSearchManager = new SearchManager(tokenAuthClient(s"ApiKey $encodedKey"), esVersionUsed)
        val searchResponse = agentSearchManager.search(".apm-agent-configuration")
        searchResponse should have statusCode 200

        val invalidateResponse = adminXpackApiManager.invalidateApiKey(apiKeyId)
        invalidateResponse should have statusCode 200

        val searchAfterInvalidateResponse = agentSearchManager.search(".apm-agent-configuration")
        searchAfterInvalidateResponse should have statusCode 403
      }
    }
    "/_security/service endpoints are called" should {
      "return service accounts list" excludeES(allEs6x, allEs7xBelowEs714x) in {
        val response = adminXpackApiManager.getServiceAccounts()
        response should have statusCode 200
        response.responseJson.obj.keys should contain("elastic/fleet-server")
      }
      "return the service account" excludeES(allEs6x, allEs7xBelowEs714x) in {
        val response = adminXpackApiManager.getServiceAccount("elastic", "fleet-server")
        response should have statusCode 200
        response.responseJson.obj.keys should contain("elastic/fleet-server")
      }
      "return service account credentials" excludeES(allEs6x, allEs7xBelowEs714x) in {
        val response = adminXpackApiManager.getServiceAccountCredentials("elastic", "fleet-server")
        response should have statusCode 200
        response.responseJson("service_account").str should be("elastic/fleet-server")
      }
      "allow creating and deleting a service account token" excludeES(allEs6x, allEs7xBelowEs714x) in {
        val documentManager = new DocumentManager(adminClient, esVersionUsed)
        documentManager.createDoc(".fleet-servers", 1, ujson.read("""{}""")).force()

        val createResponse = adminXpackApiManager.createServiceAccountToken("elastic", "fleet-server", "test-token")
        createResponse should have statusCode 200
        createResponse.responseJson("created").bool should be(true)
        createResponse.responseJson("token")("name").str should be("test-token")

        val tokenValue = createResponse.responseJson("token")("value").str
        val fleetSearchManager = new SearchManager(tokenAuthClient(s"Bearer $tokenValue"), esVersionUsed)
        val searchResponse = fleetSearchManager.search(".fleet-servers")
        searchResponse should have statusCode 200

        val deleteResponse = adminXpackApiManager.deleteServiceAccountToken("elastic", "fleet-server", "test-token")
        deleteResponse should have statusCode 200
        deleteResponse.responseJson("found").bool should be(true)

        val searchAfterDeleteResponse = fleetSearchManager.search(".fleet-servers")
        searchAfterDeleteResponse should have statusCode 403
      }
    }
  }
}
