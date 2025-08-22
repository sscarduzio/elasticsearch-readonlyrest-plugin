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

import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyWordSpecLike, SingletonPluginTestSupport}
import tech.beshu.ror.utils.JsonReader.ujsonRead
import tech.beshu.ror.utils.elasticsearch.DocumentManager
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers

class ActionsSuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport
    with ESVersionSupportForAnyWordSpecLike
    with CustomScalaTestMatchers {

  override implicit val rorConfigFileName: String = "/actions/readonlyrest.yml"

  override val nodeDataInitializer = Some { (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    documentManager.createDoc("test1_index", 1, ujsonRead("""{"hello":"world"}""")).force()
    documentManager.createDoc("test2_index", 1, ujsonRead("""{"hello":"world"}""")).force()
  }}

  private lazy val actionManager = new DocumentManager(basicAuthClient("any", "whatever"), esVersionUsed)

  "A actions rule" should {
    "work for delete request" which {
      "forbid deleting from test1_index" in {
        val result = actionManager.deleteDoc("test1_index", 1)
        result should have statusCode 403
      }
      "allow deleting from test2_index" in {
        val result = actionManager.deleteDoc("test2_index", 1)
        result should have statusCode 200
      }
    }
  }
}
