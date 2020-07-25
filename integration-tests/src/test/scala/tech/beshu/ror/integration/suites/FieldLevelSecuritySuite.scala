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
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, DocumentManagerJ, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait FieldLevelSecuritySuite
  extends WordSpec
    with BaseSingleNodeEsClusterTest {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/field_level_security/readonlyrest.yml"

  override def nodeDataInitializer = Some(FieldLevelSecuritySuite.nodeDataInitializer())

  "A fields rule" should {
    "work for simple cases" when {
      "whitelist mode is used (search API)" in {
        val searchManager = new SearchManager(basicAuthClient("user1", "pass"))
        val result = searchManager.search("/testfiltera/_search")

        assertEquals(200, result.responseCode)
        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read("""{"dummy2":"true"}"""))
      }
      "whitelist mode is used (msearach API)" in {
        val searchManager = new SearchManager(basicAuthClient("user1", "pass"))

        val result = searchManager.mSearch(
          """{"index":"testfiltera"}""",
          """{"query" : {"match_all" : {}}}""")

        assertEquals(200, result.responseCode)
        result.responses.size shouldBe 1
        val hits = result.searchHitsForResponse(0)
        val source = hits(0)("_source")

        source should be(ujson.read("""{"dummy2":"true"}"""))
      }
      "whitelist mode is used when source should not be fetched" in {
        val searchManager = new SearchManager(basicAuthClient("user1", "pass"))
        val query =
          """
            |{
            |  "_source": false
            |}
            |""".stripMargin
        val result = searchManager.search("/testfiltera/_search", query)

        assertEquals(200, result.responseCode)
        val searchJson = result.searchHits
        searchJson(0).obj.get("_source") shouldBe None

      }
      "whitelist mode is used with included blacklisted field" in {
        val searchManager = new SearchManager(basicAuthClient("user1", "pass"))

        val query =
          """
            |{
            |  "_source": "dummy"
            |}
            |""".stripMargin

        val result = searchManager.search("/testfiltera/_search", query)

        assertEquals(200, result.responseCode)
        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read("""{}"""))
      }
      "whitelist mode is used with included allowed field but it's wildcard and it's not matched" in {
        val searchManager = new SearchManager(basicAuthClient("user1", "pass"))

        val query =
          """
            |{
            |  "_source": "du*y2"
            |}
            |""".stripMargin

        val result = searchManager.search("/testfiltera/_search", query)

        assertEquals(200, result.responseCode)
        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read("""{"dummy2":"true"}"""))
      }
      "whitelist mode is used with excluded whitelisted field" in {
        val searchManager = new SearchManager(basicAuthClient("user1", "pass"))

        val query =
          """
            |{
            |  "_source": {
            |      "excludes": [ "dummy2" ]
            |  }
            |}
            |""".stripMargin

        val result = searchManager.search("/testfiltera/_search", query)

        assertEquals(200, result.responseCode)
        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read("""{}"""))
      }
      "whitelist mode with user variable is used " in {
        val searchManager = new SearchManager(basicAuthClient("dummy", "pass"))

        val result = searchManager.search("/testfiltera/_search")

        assertEquals(200, result.responseCode)
        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read("""{"dummy2":"true"}"""))
      }
      "whitelist mode is used with search query using inaccessible field" in {
        val searchManager = new SearchManager(basicAuthClient("user1", "pass"))

        val query =
          """
            |{
            |  "query": {
            |    "term": {
            |      "dummy": "a1"
            |    }
            |  }
            |}
            |""".stripMargin
        val result = searchManager.search("/testfiltera/_search", query)

        assertEquals(200, result.responseCode)

        println(result.body)
        result.searchHits.isEmpty shouldBe true
      }
      "whitelist mode with wildcard is used" in {
        val searchManager = new SearchManager(basicAuthClient("user2", "pass"))

        val result = searchManager.search("/testfiltera/_search")

        assertEquals(200, result.responseCode)
        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read("""{"dummy2":"true"}"""))
      }
      "whitelist mode is used with wildcard and with included blacklisted field" in {
        val searchManager = new SearchManager(basicAuthClient("user2", "pass"))

        val query =
          """
            |{
            |  "_source": {
            |      "includes": [ "du*y" ]
            |  }
            |}
            |""".stripMargin

        val result = searchManager.search("/testfiltera/_search", query)

        assertEquals(200, result.responseCode)
        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read("""{}"""))
      }

      "blacklist mode is used" in {
        val searchManager = new SearchManager(basicAuthClient("user3", "pass"))

        val result = searchManager.search("/testfiltera/_search")

        assertEquals(200, result.responseCode)
        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read("""{"dummy":"a1"}"""))
      }
      "blacklist mode is used with included blacklisted field search query" in {
        val searchManager = new SearchManager(basicAuthClient("user3", "pass"))

        val query =
          """
            |{
            |  "_source": {
            |      "includes": [ "dummy2" ]
            |  }
            |}
            |""".stripMargin

        val result = searchManager.search("/testfiltera/_search", query)

        assertEquals(200, result.responseCode)
        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read("""{}"""))
      }
      "blacklist mode is used with excluded whitelisted field search query" in {
        val searchManager = new SearchManager(basicAuthClient("user3", "pass"))

        val query =
          """
            |{
            |  "_source": {
            |      "excludes": [ "dummy" ]
            |  }
            |}
            |""".stripMargin

        val result = searchManager.search("/testfiltera/_search", query)

        assertEquals(200, result.responseCode)
        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read("""{}"""))
      }
      "blacklist mode with wildcard is used" in {
        val searchManager = new SearchManager(basicAuthClient("user4", "pass"))

        val result = searchManager.search("/testfiltera/_search")

        assertEquals(200, result.responseCode)
        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read("""{"dummy":"a1"}"""))
      }
    }
    "work for nested fields" when {
      "whitelist mode is used" in {
        val searchManager = new SearchManager(basicAuthClient("user1", "pass"))

        val result = searchManager.search("/nestedtest/_search")

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
            |  "secrets": [
            |    {"key":1, "text": "secret1"},
            |    {"key":2, "text": "secret2"}
            |  ],
            |  "user": {
            |     "name": "value1",
            |     "age": "value2"
            |  }
            |}
            |""".stripMargin
        ))
      }
      "whitelist mode is used with custom including" in {
        val searchManager = new SearchManager(adminClient)

        val query =
          """
            |{
            |  "_source": {
            |      "includes": [ "secrets.key" ]
            |  }
            |}
            |""".stripMargin

        val result = searchManager.search("/nestedtest/_search", query)

        assertEquals(200, result.responseCode)
        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read(
          """
            |{
            |  "secrets": [
            |    {"key":1},
            |    {"key":2}
            |  ]
            |}
            |""".stripMargin
        ))
      }
      "whitelist mode with wildcard is used" in {
        val searchManager = new SearchManager(basicAuthClient("user2", "pass"))

        val result = searchManager.search("/nestedtest/_search")

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
             |  ],
             |  "user": {
             |     "name": "value1",
             |     "age": "value2"
             |  }
             |}
           """.stripMargin
        ))
      }
      "blacklist mode is used" in {
        val searchManager = new SearchManager(basicAuthClient("user3", "pass"))

        val result = searchManager.search("/nestedtest/_search")

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

        val result = searchManager.search("/nestedtest/_search")

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
            |  "user":{}
            |}""".stripMargin
        ))
      }
    }
    "get api is used" in {
      val documentManager = new DocumentManager(basicAuthClient("user4", "pass"), targetEs.esVersion)

      val result = documentManager.get("nestedtest", 1)

      assertEquals(200, result.responseCode)
      val source = result.responseJson("_source")

      source should be(ujson.read(
        """
          |{
          |  "id":1,
          |  "items":[
          |    {"itemId":1,"text":"text1"},
          |    {"itemId":2,"text":"text2"},
          |    {"itemId":3,"text":"text3"}
          |  ],
          |  "user":{}
          |}""".stripMargin
      ))
    }
    "mget api is used" in {
      val documentManager = new DocumentManager(basicAuthClient("user4", "pass"), targetEs.esVersion)

      val result = documentManager.mGet(
        ujson.read(
          """{
            |  "docs":[
            |    {
            |      "_index":"nestedtest",
            |      "_id":1
            |    }
            |  ]
            |}""".stripMargin
        )
      )

      assertEquals(200, result.responseCode)
      val source = result.docs(0)("_source")

      source should be(ujson.read(
        """
          |{
          |  "id":1,
          |  "items":[
          |    {"itemId":1,"text":"text1"},
          |    {"itemId":2,"text":"text2"},
          |    {"itemId":3,"text":"text3"}
          |  ],
          |  "user":{}
          |}""".stripMargin
      ))
    }
  }
}

object FieldLevelSecuritySuite {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    val documentManager = new DocumentManagerJ(adminRestClient)
    documentManager.insertDocAndWaitForRefresh(
      "/testfiltera/documents/doc-a1",
      """{"dummy":"a1", "dummy2": "true"}"""
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
        |  ],
        |  "user": {
        |     "name": "value1",
        |     "age": "value2"
        |  }
        |}""".stripMargin
    )
  }
}
