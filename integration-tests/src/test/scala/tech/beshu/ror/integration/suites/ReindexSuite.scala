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
import tech.beshu.ror.utils.containers.ElasticsearchNodeDataInitializer
import tech.beshu.ror.utils.elasticsearch.IndexManager.ReindexSource
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, IndexManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers

class ReindexSuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport
    with ESVersionSupportForAnyWordSpecLike
    with CustomScalaTestMatchers {

  override implicit val rorConfigFileName: String = "/reindex/readonlyrest.yml"

  override def nodeDataInitializer = Some(ReindexSuite.nodeDataInitializer())

  private lazy val user1IndexManager = new IndexManager(basicAuthClient("dev1", "test"), esVersionUsed)

  "A reindex request" should {
    "be able to proceed" when {
      "user has permission to source index and dest index" in {
        val result = user1IndexManager.reindex(ReindexSource.Local("test1_index"), "test1_index_reindexed")

        result should have statusCode 200
      }
    }
    "not be able to proceed" when {
      "user has no permission to source index and dest index which are present on ES" in {
        val result = user1IndexManager.reindex(ReindexSource.Local("test2_index"), "test2_index_reindexed")

        result should have statusCode 403
      }
      "user has no permission to source index and dest index which are absent on ES" in {
        val result = user1IndexManager.reindex(ReindexSource.Local("not_allowed_index"), "not_allowed_index_reindexed")

        result should have statusCode 403
      }
      "user has permission to source index and but no permission to dest index" in {
        val result = user1IndexManager.reindex(ReindexSource.Local("test1_index"), "not_allowed_index_reindexed")

        result should have statusCode 403
      }
      "user has permission to dest index and but no permission to source index" in {
        val result = user1IndexManager.reindex(ReindexSource.Local("not_allowed_index"), "test1_index_reindexed")

        result should have statusCode 403
      }
    }
  }
}

object ReindexSuite {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion: String, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    documentManager.createDoc("test1_index", 1, ujsonRead("""{"hello":"world"}""")).force()
    documentManager.createDoc("test2_index", 1, ujsonRead("""{"hello":"world"}""")).force()
  }

}