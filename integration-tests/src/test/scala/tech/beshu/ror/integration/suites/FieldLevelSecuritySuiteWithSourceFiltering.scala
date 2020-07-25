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

trait FieldLevelSecuritySuiteWithSourceFiltering
  extends WordSpec
    with BaseSingleNodeEsClusterTest {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/field_level_security/readonlyrest.yml"

  override def nodeDataInitializer = Some(FieldLevelSecuritySuiteWithSourceFiltering.nodeDataInitializer())

  "A fields rule" should {
    "work for simple cases" when {
      "whitelist mode is used" in {
        val searchManager = new SearchManager(adminClient)
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

        source should be(ujson.read("""{"dummy2":"true"}"""))
      }
      "whitelist mode is used with search query using inaccessible field" in {
        val searchManager = new SearchManager(adminClient)

        val query =
          """
            |{
            |  "query": {
            |    "term": {
            |      "dummy": "a1"
            |    }
            |  },
            |  "_source": {
            |      "includes": [ "dummy2" ]
            |  }
            |}
            |""".stripMargin
        val result = searchManager.search("/testfiltera/_search", query)

        assertEquals(200, result.responseCode)
        val searchJson = result.searchHits

        val source = searchJson(0)("_source")
        source should be(ujson.read("""{"dummy2":"true"}"""))
      }
      "whitelist mode with wildcard is used" in {
        val searchManager = new SearchManager(adminClient)

        val query =
          """
            |{
            |  "_source": {
            |      "includes": [ "du*2" ]
            |  }
            |}
            |""".stripMargin

        val result = searchManager.search("/testfiltera/_search", query)

        assertEquals(200, result.responseCode)
        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read("""{"dummy2":"true"}"""))
      }
      "blacklist mode is used" in {
        val searchManager = new SearchManager(adminClient)

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

        source should be(ujson.read("""{"dummy":"a1"}"""))
      }
      "blacklist mode with wildcard is used" in {
        val searchManager = new SearchManager(adminClient)

        val query =
          """
            |{
            |  "_source": {
            |      "excludes": [ "du*2" ]
            |  }
            |}
            |""".stripMargin
        val result = searchManager.search("/testfiltera/_search", query)

        assertEquals(200, result.responseCode)
        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read("""{"dummy":"a1"}"""))
      }
    }
    "work for nested fields" when {
      "whitelist mode is used" in {
        val searchManager = new SearchManager(adminClient)

        val query =
          """
            |{
            |  "_source": {
            |      "includes": [ "items.endDate", "secrets.key", "user"],
            |      "excludes": ["secrets"]
            |  }
            |}
            |""".stripMargin
        val result = searchManager.search("/nestedtest/_search", query)

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
      "whitelist mode with wildcard is used" in {
        val searchManager = new SearchManager(adminClient)

        val query =
          """
            |{
            |  "_source": {
            |      "includes": [ "items.*Date", "secrets.*", "user.*"]
            |  }
            |}
            |""".stripMargin
        val result = searchManager.search("/nestedtest/_search", query)

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
        val searchManager = new SearchManager(adminClient)

        val query =
          """
            |{
            |  "_source": {
            |      "excludes": ["items.endDate", "secrets", "user"]
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
        val searchManager = new SearchManager(adminClient)

        val query =
          """
            |{
            |  "_source": {
            |      "excludes": ["items.*Date", "secrets.*", "user.*"]
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
            |  "id":1,
            |  "items":[
            |    {"itemId":1,"text":"text1"},
            |    {"itemId":2,"text":"text2"},
            |    {"itemId":3,"text":"text3"}
            |  ],
            |  "user": {}
            |}""".stripMargin
        ))
      }
    }

    "get api is used" in {
      val documentManager = new DocumentManager(adminClient, targetEs.esVersion)

      val queryParams = Map("_source_excludes" -> "items.*Date,secrets.*,user.*")
      val result = documentManager.get("nestedtest", 1, queryParams)

      assertEquals(200, result.responseCode)
      val source = result.responseJson("_source")
      println(result.body)

      source should be(ujson.read(
        """
          |{
          |  "id":1,
          |  "items":[
          |    {"itemId":1,"text":"text1"},
          |    {"itemId":2,"text":"text2"},
          |    {"itemId":3,"text":"text3"}
          |  ],
          |  "user": {}
          |}""".stripMargin
      ))
    }
    "mget api is used" in {
      val documentManager = new DocumentManager(adminClient, targetEs.esVersion)

      val queryParams = Map("_source_excludes" -> "items.*Date,secrets.*,user.*")

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
        ),
        queryParams
      )

      assertEquals(200, result.responseCode)
      val source = result.docs(0)("_source")
      println(result.body)

      source should be(ujson.read(
        """
          |{
          |  "id":1,
          |  "items":[
          |    {"itemId":1,"text":"text1"},
          |    {"itemId":2,"text":"text2"},
          |    {"itemId":3,"text":"text3"}
          |  ],
          |  "user": {}
          |}""".stripMargin
      ))
    }

  }
}

object FieldLevelSecuritySuiteWithSourceFiltering {
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


