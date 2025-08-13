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
import tech.beshu.ror.utils.containers.SecurityType
import tech.beshu.ror.utils.containers.images.ReadonlyRestWithEnabledXpackSecurityPlugin
import tech.beshu.ror.utils.containers.images.domain.Enabled

class XpackApiWithRorWithEnabledXpackSecuritySuite extends BaseXpackApiSuite {

  override implicit val rorConfigFileName: String = "/xpack_api/readonlyrest_without_ror_ssl.yml"

  override protected def rorClusterSecurityType: SecurityType =
    SecurityType.RorWithXpackSecurity(ReadonlyRestWithEnabledXpackSecurityPlugin.Config.Attributes.default.copy(
      rorConfigFileName = rorConfigFileName,
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
        response.responseJson should be(ujson.read(
          s"""
             |{
             |  "username": "ROR",
             |  "has_all_requested": true,
             |  "cluster": {
             |    "monitor": true
             |  },
             |  "index": {
             |    ".monitoring-*-6-*,.monitoring-*-7-*": {
             |      "read": true
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
    "API key grant request is called" should {
      "be allowed" excludeES allEs6x in {
        val response = adminXpackApiManager.grantApiKeyPrivilege("admin", "admin")
        response should have statusCode 200
      }
    }
  }
}
