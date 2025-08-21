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
import tech.beshu.ror.utils.containers.ElasticsearchNodeDataInitializer
import tech.beshu.ror.utils.JsonReader.ujsonRead
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, IndexManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers

class ShrinkIndexApiSuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport
    with ESVersionSupportForAnyWordSpecLike
    with CustomScalaTestMatchers {

  override implicit val rorConfigFileName: String = "/shrink_api/readonlyrest.yml"

  private lazy val user1IndexManager = new IndexManager(basicAuthClient("dev1", "test"), esVersionUsed)
  private lazy val user2IndexManager = new IndexManager(basicAuthClient("dev2", "test"), esVersionUsed)

  override def nodeDataInitializer = Some(ShrinkIndexApiSuite.nodeDataInitializer())

  "A Shrink API request" should {
    "be able to proceed" when {
      "user has permission to source index and dest index" when {
        "wildcard is used" in {
          val result = user2IndexManager.shrink(sourceIndex = "test2_index", targetIndex = "test2_index_resized")

          result should have statusCode 200
        }
        "alias is used" in {
          val result = user1IndexManager.shrink(sourceIndex = "test1_index", targetIndex = "test1_index_resized", aliases = "test1_index_allowed_alias" :: Nil)

          result should have statusCode 200
        }
      }
    }
    "not be able to proceed" when {
      "user has permission to source index and dest index but no permission to alias" in {
        val result = user1IndexManager.shrink(sourceIndex = "test1_index", targetIndex = "test1_index_resized", aliases = "test1_index_not_allowed_alias" :: Nil)

        result should have statusCode 403
      }
      "user has permission to source index and dest index but no permission to one alias" in {
        val result = user1IndexManager.shrink(sourceIndex = "test1_index", targetIndex = "test1_index_resized", aliases = "test1_index_not_allowed_alias" :: "test1_index_allowed_alias" :: Nil)

        result should have statusCode 403
      }
      "user has no permission to source index and dest index which are present on ES" in {
        val result = user1IndexManager.shrink(sourceIndex = "test2_index", targetIndex = "test2_index_resized")

        result should have statusCode 403
      }
      "user has no permission to source index and dest index which are absent on ES" in {
        val result = user1IndexManager.shrink(sourceIndex = "not_allowed_index", targetIndex = "not_allowed_index_resized")

        result should have statusCode 403
      }
      "user has permission to source index but no permission to dest index" in {
        val result = user1IndexManager.shrink(sourceIndex = "test1_index", targetIndex = "not_allowed_index_resized")

        result should have statusCode 403
      }
      "user has permission to dest index and but no permission to source index" in {
        val result = user1IndexManager.shrink(sourceIndex = "not_allowed_index", targetIndex = "test1_index_resized")

        result should have statusCode 403
      }
    }
  }
}

object ShrinkIndexApiSuite {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion: String, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    val indexManager = new IndexManager(adminRestClient, esVersion)

    val shardSettings =
      ujsonRead {
        s"""
           |{
           |  "settings": {
           |    "index": {
           |      "number_of_shards": 3
           |    }
           |  }
           |}
          """.stripMargin
      }

    indexManager.createIndex("test1_index", settings = Some(shardSettings)).force()
    indexManager.createIndex("test2_index", settings = Some(shardSettings)).force()
    documentManager.createDoc("test1_index", 1, ujsonRead("""{"hello":"world"}""")).force()
    documentManager.createDoc("test2_index", 1, ujsonRead("""{"hello":"world"}""")).force()
    indexManager.putSettingsIndexBlocksWrite("test1_index", indexBlockWrite = true).force()
    indexManager.putSettingsIndexBlocksWrite("test2_index", indexBlockWrite = true).force()
  }
}