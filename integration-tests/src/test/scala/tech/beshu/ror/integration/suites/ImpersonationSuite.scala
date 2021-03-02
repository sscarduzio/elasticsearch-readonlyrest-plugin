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
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.TestSuiteWithClosedTaskAssertion
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{DocumentManagerJ, SearchManagerJ}
import tech.beshu.ror.utils.httpclient.RestClient

import scala.collection.JavaConverters._

trait ImpersonationSuite
  extends AnyWordSpec
    with TestSuiteWithClosedTaskAssertion
    with BaseSingleNodeEsClusterTest {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/impersonation/readonlyrest.yml"

  override def nodeDataInitializer = Some(ImpersonationSuite.nodeDataInitializer())

  "Impersonation can be done" when {
    "user uses local auth rule" when {
      "impersonator can be properly authenticated" in {
        val searchManager = new SearchManagerJ(
          basicAuthClient("admin1", "pass"),
          Map("impersonate_as" -> "dev1").asJava
        )

        val result = searchManager.search("/test1_index/_search")

        assertEquals(200, result.getResponseCode)
      }
    }
  }
  "Impersonation cannot be done" when {
    "there is no such user with admin privileges" in {
      val searchManager = new SearchManagerJ(
        basicAuthClient("unknown", "pass"),
        Map("impersonate_as" -> "dev1").asJava
      )

      val result = searchManager.search("/test1_index/_search")

      assertEquals(401, result.getResponseCode)
      assertEquals(1, result.getError.size())
      result.getError.get(0).asScala("reason") should be("forbidden")
      result.getError.get(0).asScala("due_to").asInstanceOf[java.util.List[String]].asScala should contain("IMPERSONATION_NOT_ALLOWED")
    }
    "user with admin privileges cannot be authenticated" in {
      val searchManager = new SearchManagerJ(
        basicAuthClient("admin1", "wrong_pass"),
        Map("impersonate_as" -> "dev1").asJava
      )

      val result = searchManager.search("/test1_index/_search")

      assertEquals(401, result.getResponseCode)
      assertEquals(1, result.getError.size())
      result.getError.get(0).asScala("reason") should be("forbidden")
      result.getError.get(0).asScala("due_to").asInstanceOf[java.util.List[String]].asScala should contain("IMPERSONATION_NOT_ALLOWED")
    }
    "admin user is authenticated but cannot impersonate given user" in {
      val searchManager = new SearchManagerJ(
        basicAuthClient("admin2", "pass"),
        Map("impersonate_as" -> "dev1").asJava
      )

      val result = searchManager.search("/test1_index/_search")

      assertEquals(401, result.getResponseCode)
      assertEquals(1, result.getError.size())
      result.getError.get(0).asScala("reason") should be("forbidden")
      result.getError.get(0).asScala("due_to").asInstanceOf[java.util.List[String]].asScala should contain("IMPERSONATION_NOT_ALLOWED")
    }
    "not supported authentication rule is used" which {
      "is full hashed auth credentials" in {
        val searchManager = new SearchManagerJ(
          basicAuthClient("admin1", "pass"),
          Map("impersonate_as" -> "dev1").asJava
        )

        val result = searchManager.search("/test2_index/_search")
        assertEquals(401, result.getResponseCode)
        assertEquals(1, result.getError.size())
        result.getError.get(0).asScala("reason") should be("forbidden")
        result.getError.get(0).asScala("due_to").asInstanceOf[java.util.List[String]].asScala should contain("IMPERSONATION_NOT_SUPPORTED")
      }
    }
  }
}

object ImpersonationSuite {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    val documentManager = new DocumentManagerJ(adminRestClient)
    documentManager.insertDoc("/test1_index/test/1", "{\"hello\":\"world\"}")
    documentManager.insertDoc("/test2_index/test/1", "{\"hello\":\"world\"}")
  }
}