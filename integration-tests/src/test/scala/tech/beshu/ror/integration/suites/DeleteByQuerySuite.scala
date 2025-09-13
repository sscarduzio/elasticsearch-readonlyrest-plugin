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
import tech.beshu.ror.utils.TestUjson.ujson
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, ElasticsearchTweetsInitializer}
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers

class DeleteByQuerySuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport
    with ESVersionSupportForAnyWordSpecLike
    with CustomScalaTestMatchers {

  private val matchAllQuery = ujson.read("""{"query" : {"match_all" : {}}}""".stripMargin)

  override implicit val rorConfigFileName: String = "/delete_by_query/readonlyrest.yml"

  override def nodeDataInitializer = Some(ElasticsearchTweetsInitializer)

  private lazy val blueTeamDeleteByQueryManager = new DocumentManager(basicAuthClient("blue", "dev"), esVersionUsed)
  private lazy val redTeamDeleteByQueryManager = new DocumentManager(basicAuthClient("red", "dev"), esVersionUsed)

  "Delete by query" should {
    "be allowed" when {
      "is executed by blue client" in {
        val response = blueTeamDeleteByQueryManager.deleteByQuery("twitter", matchAllQuery)
        response should have statusCode 200
      }
    }
    "not be allowed" when {
      "is executed by red client" in {
        val response = redTeamDeleteByQueryManager.deleteByQuery("facebook", matchAllQuery)
        response should have statusCode 403
      }
    }
  }
}
