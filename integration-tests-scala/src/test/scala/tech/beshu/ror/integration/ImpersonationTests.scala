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
package tech.beshu.ror.integration

import com.dimafeng.testcontainers.ForAllTestContainer
import org.junit.Assert.assertEquals
import org.scalatest.WordSpec
import org.scalatest.Matchers._
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, ReadonlyRestEsCluster, ReadonlyRestEsClusterContainer}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient

import scala.collection.JavaConverters._

class ImpersonationTests extends WordSpec with ForAllTestContainer {

  override val container: ReadonlyRestEsClusterContainer = ReadonlyRestEsCluster.createLocalClusterContainer(
    name = "ROR1",
    rorConfigFileName = "/impersonation/readonlyrest.yml",
    numberOfInstances = 1,
    ImpersonationTests.nodeDataInitializer()
  )

  "Impersonation can be done" when {
    "user uses local auth rule" when {
      "impersonator can be properly authenticated" in {
        val searchManager = new SearchManager(
          container.nodesContainers.head.client("admin1", "pass"),
          Map("impersonate_as" -> "dev1").asJava
        )

        val result = searchManager.search("/test1_index/_search")

        assertEquals(200, result.getResponseCode)
      }
    }
  }
  "Impersonation cannot be done" when {
    "there is no such user with admin privileges" in {
      val searchManager = new SearchManager(
        container.nodesContainers.head.client("unknown", "pass"),
        Map("impersonate_as" -> "dev1").asJava
      )

      val result = searchManager.search("/test1_index/_search")

      assertEquals(401, result.getResponseCode)
      assertEquals(1, result.getError.size())
      result.getSearchHits.get(0).asScala("reason") should be ("forbidden")
      result.getSearchHits.get(0).asScala("due_to").asInstanceOf[java.util.List[String]].asScala should contain ("IMPERSONATION_NOT_ALLOWED")
    }
    "user with admin privileges cannot be authenticated" in {
      val searchManager = new SearchManager(
        container.nodesContainers.head.client("admin1", "wrong_pass"),
        Map("impersonate_as" -> "dev1").asJava
      )

      val result = searchManager.search("/test1_index/_search")

      assertEquals(401, result.getResponseCode)
      assertEquals(1, result.getError.size())
      result.getSearchHits.get(0).asScala("reason") should be ("forbidden")
      result.getSearchHits.get(0).asScala("due_to").asInstanceOf[java.util.List[String]].asScala should contain ("IMPERSONATION_NOT_ALLOWED")
    }
    "admin user is authenticated but cannot impersonate given user" in {
      val searchManager = new SearchManager(
        container.nodesContainers.head.client("admin2", "pass"),
        Map("impersonate_as" -> "dev1").asJava
      )

      val result = searchManager.search("/test1_index/_search")

      assertEquals(401, result.getResponseCode)
      assertEquals(1, result.getError.size())
      result.getSearchHits.get(0).asScala("reason") should be ("forbidden")
      result.getSearchHits.get(0).asScala("due_to").asInstanceOf[java.util.List[String]].asScala should contain ("IMPERSONATION_NOT_ALLOWED")
    }
    "not supported authentication rule is used" which {
      "is full hashed auth credentials" in {
        val searchManager = new SearchManager(
          container.nodesContainers.head.client("admin1", "pass"),
          Map("impersonate_as" -> "dev1").asJava
        )

        val result = searchManager.search("/test2_index/_search")
        assertEquals(401, result.getResponseCode)
        assertEquals(1, result.getError.size())
        result.getSearchHits.get(0).asScala("reason") should be ("forbidden")
        result.getSearchHits.get(0).asScala("due_to").asInstanceOf[java.util.List[String]].asScala should contain ("IMPERSONATION_NOT_SUPPORTED")
      }
    }
  }
}

object ImpersonationTests {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient)
    documentManager.insertDoc("/test1_index/test/1", "{\"hello\":\"world\"}")
    documentManager.insertDoc("/test2_index/test/1", "{\"hello\":\"world\"}")
  }
}