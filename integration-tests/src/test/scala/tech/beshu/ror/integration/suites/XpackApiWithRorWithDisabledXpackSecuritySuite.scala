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
import tech.beshu.ror.utils.containers.images.ReadonlyRestPlugin.Config.{Attributes, InternodeSsl, RestSsl}
import tech.beshu.ror.utils.containers.images.domain.{Enabled, SourceFile}
import tech.beshu.ror.utils.elasticsearch.IndexManager

class XpackApiWithRorWithDisabledXpackSecuritySuite extends BaseXpackApiSuite {

  override implicit val rorSettingsFileName: String = "/xpack_api/readonlyrest_with_ror_ssl.yml"

  override protected def rorClusterSecurityType: SecurityType =
    SecurityType.RorSecurity(Attributes.default.copy(
      rorSettingsFileName = rorSettingsFileName,
      restSsl = Enabled.Yes(RestSsl.Ror(SourceFile.EsFile)),
      internodeSsl = Enabled.Yes(InternodeSsl.Ror(SourceFile.EsFile))
    ))

  "Search API" when {
    "request with remote indices pattern and local indices pattern is called" should {
      "return illegal_argument_exception when there are no remote clusters configured" excludeES(allEs6x, allEs7x) in {
        val adminIndexManager = new IndexManager(adminClient, esVersionUsed)
        val result = adminIndexManager.getIndex("*", "*:*")
        result should have statusCode 400
        result.responseJson("error")("type").str should be("illegal_argument_exception")
        result.responseJson("error")("reason").str should include("Cross-cluster calls are not supported in this context but remote indices were requested: [*:*]")
      }
      "return indices successfully (no remote indices) when there are no remote clusters configured" excludeES(allEs8x, allEs9x) in {
        val adminIndexManager = new IndexManager(adminClient, esVersionUsed)
        val result = adminIndexManager.getIndex("*", "*:*")
        result should have statusCode 200
      }
    }
    "request with only remote indices pattern is called" should {
      "return illegal_argument_exception when there are no remote clusters configured" excludeES(allEs6x, allEs7x) in {
        val adminIndexManager = new IndexManager(adminClient, esVersionUsed)
        val result = adminIndexManager.getIndex("*:*")
        result should have statusCode 400
        result.responseJson("error")("type").str should be("illegal_argument_exception")
        result.responseJson("error")("reason").str should include("Cross-cluster calls are not supported in this context but remote indices were requested: [*:*]")
      }
      "return no indices when there are no remote clusters configured" excludeES(allEs8x, allEs9x) in {
        val adminIndexManager = new IndexManager(adminClient, esVersionUsed)
        val result = adminIndexManager.getIndex("*:*")
        result should have statusCode 200
        result.indicesAndAliases should be(Map.empty)
      }
    }
  }
}
