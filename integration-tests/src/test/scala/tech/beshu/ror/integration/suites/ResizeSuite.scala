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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.ESVersionSupportForAnyWordSpecLike
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsClusterProvider}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, IndexManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait ResizeSuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with ESVersionSupportForAnyWordSpecLike
    with Matchers {
  this: EsClusterProvider =>

  override implicit val rorConfigFileName = "/resize/readonlyrest.yml"

  private lazy val user1IndexManager = new IndexManager(basicAuthClient("dev1", "test"), esVersionUsed)
  private lazy val user2IndexManager = new IndexManager(basicAuthClient("dev2", "test"), esVersionUsed)


  override def nodeDataInitializer = Some(ResizeSuite.nodeDataInitializer())

  "A resize request" should {
    "be able to proceed" when {
      "user has permission to source index and dest index" when {
        "wildcard is used" in {
          val result = user2IndexManager.resize(source="test2_index", target="test2_index_resized")

          result.responseCode should be(200)
        }

        "alias is used" in {
          val result = user1IndexManager.resize(source="test1_index", target="test1_index_resized", aliases="test1_index_allowed_alias" :: Nil)

          result.responseCode should be(200)
        }
      }
    }
    "not be able to proceed" when {
      "user has permission to source index and dest index but no permission to alias" in {
        val result = user1IndexManager.resize(source="test1_index", target="test1_index_resized", aliases="test1_index_not_allowed_alias" :: Nil)

        result.responseCode should be(401)
      }
      "user has permission to source index and dest index but no permission to one alias" in {
        val result = user1IndexManager.resize(source="test1_index", target="test1_index_resized", aliases="test1_index_not_allowed_alias" :: "test1_index_allowed_alias" :: Nil)

        result.responseCode should be(401)
      }
      "user has no permission to source index and dest index which are present on ES"  in {
        val result = user1IndexManager.resize(source="test2_index", target="test2_index_resized")

        result.responseCode should be(401)
      }
      "user has no permission to source index and dest index which are absent on ES"  in {
        val result = user1IndexManager.resize(source="not_allowed_index", target="not_allowed_index_resized")

        result.responseCode should be(401)
      }
      "user has permission to source index but no permission to dest index"  in {
        val result = user1IndexManager.resize(source="test1_index", target="not_allowed_index_resized")

        result.responseCode should be(401)
      }
      "user has permission to dest index and but no permission to source index"  in {
        val result = user1IndexManager.resize(source="not_allowed_index", target="test1_index_resized")

        result.responseCode should be(401)
      }
    }
  }
}

object ResizeSuite {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion: String, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    val indexManager = new IndexManager(adminRestClient, esVersion)

    val shardSettings =
      ujson.read {
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

    val readOnlySetttings =
      ujson.read {
        s"""
           |{
           |  "settings": {
           |    "index.blocks.write": true
           |  }
           |}
          """.stripMargin
      }

    indexManager.createIndex("test1_index", settings = Some(shardSettings)).force()
    indexManager.createIndex("test2_index", settings = Some(shardSettings)).force()
    documentManager.createDoc("test1_index", 1, ujson.read("""{"hello":"world"}""")).force()
    documentManager.createDoc("test2_index", 1, ujson.read("""{"hello":"world"}""")).force()
    indexManager.putSettings("test1_index", settings = readOnlySetttings).force()
    indexManager.putSettings("test2_index", settings = readOnlySetttings).force()
  }
}