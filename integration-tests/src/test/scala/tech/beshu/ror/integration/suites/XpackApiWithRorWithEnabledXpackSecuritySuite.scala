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
import tech.beshu.ror.utils.misc.Version

class XpackApiWithRorWithEnabledXpackSecuritySuite extends BaseXpackApiSuite {

  override implicit val rorConfigFileName: String = "/xpack_api/readonlyrest_without_ror_ssl.yml"

  override protected def rorClusterSecurityType: SecurityType =
    SecurityType.RorWithXpackSecurity(ReadonlyRestWithEnabledXpackSecurityPlugin.Config.Attributes.default.copy(
      rorSettingsFileName = rorConfigFileName,
      restSsl = Enabled.Yes(ReadonlyRestWithEnabledXpackSecurityPlugin.Config.RestSsl.Xpack),
      internodeSsl = Enabled.Yes(ReadonlyRestWithEnabledXpackSecurityPlugin.Config.InternodeSsl.Xpack)
    ))

  "Security API" when {
    "_has_privileges endpoint is called" should {
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
        if (Version.greaterOrEqualThan(esVersionUsed, 8, 9, 0)) {
          response.responseJson should be(ujson.read(
            s"""
               |{
               |  "username":"_xpack",
               |  "has_all_requested":false,
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
               |        "login:":false,
               |        "version:$esVersionUsed":false
               |      }
               |    }
               |  }
               |}
               |""".stripMargin
          ))
        } else {
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
    }
    "user/_privileges endpoint is called" should {
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
  }
}
