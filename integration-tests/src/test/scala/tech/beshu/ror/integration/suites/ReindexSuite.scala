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

import org.junit.Assert.assertEquals
import org.scalatest.{Matchers, WordSpec}
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.ESVersionSupport
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{ActionManagerJ, DocumentManagerJ}
import tech.beshu.ror.utils.httpclient.RestClient

trait ReindexSuite
  extends WordSpec
    with BaseSingleNodeEsClusterTest
    with ESVersionSupport
    with Matchers {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/reindex/readonlyrest.yml"

  override def nodeDataInitializer = Some(ReindexSuite.nodeDataInitializer())

  private lazy val user1ActionManager = new ActionManagerJ(basicAuthClient("dev1", "test"))

  "A reindex request" should {
    "be able to proceed" when {
      "user has permission to source index and dest index"  in {
        val result = user1ActionManager.actionPost("_reindex", ReindexSuite.reindexPayload("test1_index", "test1_index_reindexed"))
        assertEquals(200, result.getResponseCode)
      }
    }
    "not be able to proceed" when {
      "user has no permission to source index and dest index which are present on ES"  in {
        val result = user1ActionManager.actionPost("_reindex", ReindexSuite.reindexPayload("test2_index", "test2_index_reindexed"))
        assertEquals(401, result.getResponseCode)
      }
      "user has no permission to source index and dest index which are absent on ES"  in {
        val result = user1ActionManager.actionPost("_reindex", ReindexSuite.reindexPayload("not_allowed_index", "not_allowed_index_reindexed"))
        assertEquals(401, result.getResponseCode)
      }
      "user has permission to source index and but no permission to dest index"  in {
        val result = user1ActionManager.actionPost("_reindex", ReindexSuite.reindexPayload("test1_index", "not_allowed_index_reindexed"))
        assertEquals(401, result.getResponseCode)
      }
      "user has permission to dest index and but no permission to source index"  in {
        val result = user1ActionManager.actionPost("_reindex", ReindexSuite.reindexPayload("not_allowed_index", "test1_index_reindexed"))
        assertEquals(401, result.getResponseCode)
      }
    }
  }
}

object ReindexSuite {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    val documentManager = new DocumentManagerJ(adminRestClient)
    documentManager.insertDoc("/test1_index/test/1", "{\"hello\":\"world\"}")
    documentManager.insertDoc("/test2_index/test/1", "{\"hello\":\"world\"}")
  }

  private def reindexPayload(sourceIndexName: String, destIndexName: String) = {
    s"""{"source":{"index":"$sourceIndexName"},"dest":{"index":"${destIndexName}"}}"""
  }
}