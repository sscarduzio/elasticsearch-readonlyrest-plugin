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
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{DocumentManagerJ, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait FieldLevelSecuritySuite
  extends WordSpec
    with BaseSingleNodeEsClusterTest {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/field_level_security/readonlyrest.yml"

  override def nodeDataInitializer = Some(FieldLevelSecuritySuite.nodeDataInitializer())

  "A fields rule" should {
    "work for simple cases" when {
      "whitelist mode is used" in {
        val searchManager = new SearchManager(basicAuthClient("user1", "pass"))

        val result = searchManager.search("testfiltera")

        assertEquals(200, result.responseCode)
        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read("""{"user1":"user1Value"}"""))
      }
      "whitelist mode with user variable is used " in {
        val searchManager = new SearchManager(basicAuthClient("user2", "pass"))

        val result = searchManager.search("/testfiltera")

        assertEquals(200, result.responseCode)
        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read("""{"user2":"user2Value"}"""))
      }
      "whitelist mode with user variable is used and called by user with 'negated' value" in {
        val searchManager = new SearchManager(basicAuthClient("~user", "pass"))

        val result = searchManager.search("/testfiltera")

        assertEquals(200, result.responseCode)
        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read("""{"~user":"~userValue"}"""))
      }
      "whitelist mode with wildcard is used" in {
        val searchManager = new SearchManager(basicAuthClient("user3", "pass"))

        val result = searchManager.search("testfiltera")

        assertEquals(200, result.responseCode)
        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read("""{"user3":"user3Value"}"""))
      }
      "blacklist mode is used" in {
        val searchManager = new SearchManager(basicAuthClient("user4", "pass"))

        val result = searchManager.search("testfiltera")

        assertEquals(200, result.responseCode)
        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read(
          """|{
             | "~user": "~userValue",
             | "user1": "user1Value",
             | "user2": "user2Value",
             | "user3": "user3Value",
             | "user5": "user5Value",
             | "user6": "user6Value"
             |}""".stripMargin)
        )
      }
      "blacklist mode with user variable is used " in {
        val searchManager = new SearchManager(basicAuthClient("user5", "pass"))

        val result = searchManager.search("/testfiltera")

        assertEquals(200, result.responseCode)
        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read(
          """|{
             | "~user": "~userValue",
             | "user1": "user1Value",
             | "user2": "user2Value",
             | "user3": "user3Value",
             | "user4": "user4Value",
             | "user6": "user6Value"
             |}""".stripMargin)
        )
      }
      "blacklist mode with wildcard is used" in {
        val searchManager = new SearchManager(basicAuthClient("user6", "pass"))

        val result = searchManager.search("testfiltera")

        assertEquals(200, result.responseCode)
        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read(
          """|{
             | "~user": "~userValue",
             | "user1": "user1Value",
             | "user2": "user2Value",
             | "user3": "user3Value",
             | "user4": "user4Value",
             | "user5": "user5Value"
             |}""".stripMargin)
        )
      }
    }
    "work for nested fields" when {
      "whitelist mode is used" in {
        val searchManager = new SearchManager(basicAuthClient("user1", "pass"))

        val result = searchManager.search("nestedtest")

        assertEquals(200, result.responseCode)
        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read(
          """
            |{
            |  "items":[
            |    {"endDate":"2019-07-31"},
            |    {"endDate":"2019-06-30"},
            |    {"endDate":"2019-09-30"}
            |  ],
            |  "secrets":[{},{}]
            |}
            |""".stripMargin
        ))
      }
      "whitelist mode with wildcard is used" in {
        val searchManager = new SearchManager(basicAuthClient("user2", "pass"))

        val result = searchManager.search("nestedtest")

        assertEquals(200, result.responseCode)
        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read(
          s"""
             |{
             |  "items":[
             |    {"startDate":"2019-05-22","endDate":"2019-07-31"},
             |    {"startDate":"2019-05-22","endDate":"2019-06-30"},
             |    {"startDate":"2019-05-22","endDate":"2019-09-30"}
             |  ],
             |  "secrets":[
             |    {"key":1,"text":"secret1"},
             |    {"key":2,"text":"secret2"}
             |  ]
             |}
           """.stripMargin
        ))
      }
      "blacklist mode is used" in {
        val searchManager = new SearchManager(basicAuthClient("user3", "pass"))

        val result = searchManager.search("nestedtest")

        assertEquals(200, result.responseCode)
        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read(
          """
            |{
            |  "id":1,
            |  "items":[
            |    {"itemId":1,"text":"text1","startDate":"2019-05-22"},
            |    {"itemId":2,"text":"text2","startDate":"2019-05-22"},
            |    {"itemId":3,"text":"text3","startDate":"2019-05-22"}
            |  ]
            |}""".stripMargin
        ))
      }
      "blacklist mode with wildcards is used" in {
        val searchManager = new SearchManager(basicAuthClient("user4", "pass"))

        val result = searchManager.search("nestedtest")

        assertEquals(200, result.responseCode)
        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read(
          """
            |{
            |  "id":1,
            |  "items":[
            |    {"itemId":1,"text":"text1"},
            |    {"itemId":2,"text":"text2"},
            |    {"itemId":3,"text":"text3"}
            |  ],
            |  "secrets":[{},{}]
            |}""".stripMargin
        ))
      }
    }
  }
}

object FieldLevelSecuritySuite {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    val documentManager = new DocumentManagerJ(adminRestClient)
    documentManager.insertDocAndWaitForRefresh(
      "/testfiltera/documents/doc-a1",
      """{
        | "~user": "~userValue",
        | "user1": "user1Value",
        | "user2": "user2Value",
        | "user3": "user3Value",
        | "user4": "user4Value",
        | "user5": "user5Value",
        | "user6": "user6Value"
        |}""".stripMargin
    )
    documentManager.insertDocAndWaitForRefresh(
      "/nestedtest/documents/1",
      """
        |{
        |  "id":1,
        |  "items": [
        |    {"itemId": 1, "text":"text1", "startDate": "2019-05-22", "endDate": "2019-07-31"},
        |    {"itemId": 2, "text":"text2", "startDate": "2019-05-22", "endDate": "2019-06-30"},
        |    {"itemId": 3, "text":"text3", "startDate": "2019-05-22", "endDate": "2019-09-30"}
        |  ],
        |  "secrets": [
        |    {"key":1, "text": "secret1"},
        |    {"key":2, "text": "secret2"}
        |  ]
        |}""".stripMargin
    )
  }
}
