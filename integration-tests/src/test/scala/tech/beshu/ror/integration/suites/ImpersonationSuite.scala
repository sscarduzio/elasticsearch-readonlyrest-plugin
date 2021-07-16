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
import tech.beshu.ror.integration.suites.base.TestSuiteWithClosedTaskAssertion
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.BaseManager.SimpleHeader
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient

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
        val searchManager = new SearchManager(
          basicAuthClient("admin1", "pass"),
          Map("impersonate_as" -> "dev1")
        )

        val result = searchManager.search("test1_index")

        result.responseCode should be (200)
      }
    }
  }
  "Impersonation cannot be done" when {
    "there is no such user with admin privileges" in {
      val searchManager = new SearchManager(
        basicAuthClient("unknown", "pass"),
        Map("impersonate_as" -> "dev1")
      )

      val result = searchManager.search("test1_index")

      result.responseCode should be (401)
      result.responseJson should be (ujson.read(
        """
          |{
          |  "error":{
          |    "root_cause":[
          |      {
          |        "type":"forbidden_response",
          |        "reason":"forbidden",
          |        "due_to":"IMPERSONATION_NOT_ALLOWED",
          |        "header":{"WWW-Authenticate":"Basic"}
          |      }
          |    ],
          |    "type":"forbidden_response",
          |    "reason":"forbidden",
          |    "due_to":"IMPERSONATION_NOT_ALLOWED",
          |    "header":{"WWW-Authenticate":"Basic"}
          |  },
          |  "status":401
          |}
        """.stripMargin))
      result.headers should be (Set(
        SimpleHeader("WWW-Authenticate", "Basic"),
        SimpleHeader("content-type", "application/json; charset=UTF-8"),
      ))
    }
    "user with admin privileges cannot be authenticated" in {
      val searchManager = new SearchManager(
        basicAuthClient("admin1", "wrong_pass"),
        Map("impersonate_as" -> "dev1")
      )

      val result = searchManager.search("test1_index")

      result.responseCode should be (401)
      result.responseJson should be (ujson.read(
        """
          |{
          |  "error":{
          |    "root_cause":[
          |      {
          |        "type":"forbidden_response",
          |        "reason":"forbidden",
          |        "due_to":"IMPERSONATION_NOT_ALLOWED",
          |        "header":{"WWW-Authenticate":"Basic"}
          |      }
          |    ],
          |    "type":"forbidden_response",
          |    "reason":"forbidden",
          |    "due_to": "IMPERSONATION_NOT_ALLOWED",
          |    "header":{"WWW-Authenticate":"Basic"}
          |  },
          |  "status":401
          |}
        """.stripMargin))
      result.headers should be (Set(
        SimpleHeader("WWW-Authenticate", "Basic"),
        SimpleHeader("content-type", "application/json; charset=UTF-8"),
      ))
    }
    "admin user is authenticated but cannot impersonate given user" in {
      val searchManager = new SearchManager(
        basicAuthClient("admin2", "pass"),
        Map("impersonate_as" -> "dev1")
      )

      val result = searchManager.search("test1_index")

      result.responseCode should be (401)
      result.responseJson should be (ujson.read(
        """
          |{
          |  "error":{
          |    "root_cause":[
          |      {
          |        "type":"forbidden_response",
          |        "reason":"forbidden",
          |        "due_to":"IMPERSONATION_NOT_ALLOWED",
          |        "header":{"WWW-Authenticate":"Basic"}
          |      }
          |    ],
          |    "type":"forbidden_response",
          |    "reason":"forbidden",
          |    "due_to":"IMPERSONATION_NOT_ALLOWED",
          |    "header":{"WWW-Authenticate":"Basic"}
          |  },
          |  "status":401
          |}
        """.stripMargin))
      result.headers should be (Set(
        SimpleHeader("WWW-Authenticate", "Basic"),
        SimpleHeader("content-type", "application/json; charset=UTF-8"),
      ))
    }
    "not supported authentication rule is used" which {
      "is full hashed auth credentials" in {
        val searchManager = new SearchManager(
          basicAuthClient("admin1", "pass"),
          Map("impersonate_as" -> "dev1")
        )

        val result = searchManager.search("test2_index")

        result.responseCode should be (401)
        result.responseJson should be (ujson.read(
          """
            |{
            |  "error":{
            |    "root_cause":[
            |      {
            |        "type":"forbidden_response",
            |        "reason":"forbidden",
            |        "due_to":["OPERATION_NOT_ALLOWED", "IMPERSONATION_NOT_SUPPORTED"],
            |        "header":{"WWW-Authenticate":"Basic"}
            |      }
            |    ],
            |    "type":"forbidden_response",
            |    "reason":"forbidden",
            |    "due_to":["OPERATION_NOT_ALLOWED", "IMPERSONATION_NOT_SUPPORTED"],
            |    "header":{"WWW-Authenticate":"Basic"}
            |  },
            |  "status":401
            |}
          """.stripMargin))
        result.headers should be (Set(
          SimpleHeader("WWW-Authenticate", "Basic"),
          SimpleHeader("content-type", "application/json; charset=UTF-8"),
        ))
      }
    }
  }
}

object ImpersonationSuite {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion: String, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    documentManager.createDoc("test1_index", 1, ujson.read("""{"hello":"world"}""")).force()
    documentManager.createDoc("test2_index", 1, ujson.read("""{"hello":"world"}""")).force()
  }
}