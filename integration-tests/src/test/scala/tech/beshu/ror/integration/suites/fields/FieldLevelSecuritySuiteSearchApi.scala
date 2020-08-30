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
package tech.beshu.ror.integration.suites.fields

import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait FieldLevelSecuritySuiteSearchApi
  extends WordSpec
    with BaseSingleNodeEsClusterTest {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/field_level_security/readonlyrest.yml"

  override def nodeDataInitializer = Some(FieldLevelSecuritySuiteSearchApi.nodeDataInitializer())

  "A fields rule" should {
    "work for simple cases" when {
      "whitelist mode is used" in {
        val searchManager = new SearchManager(basicAuthClient("user1", "pass"))

        val result = searchManager.search("testfiltera")

        result.responseCode shouldBe 200

        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read("""{"user1":"user1Value"}"""))
      }
      "whitelist mode is used when source should not be fetched" in {
        val searchManager = new SearchManager(basicAuthClient("user1", "pass"))
        val query = ujson.read(
          """
            |{
            |  "_source": false
            |}
            |""".stripMargin
        )
        val result = searchManager.search("testfiltera", query)

        result.responseCode shouldBe 200

        val searchJson = result.searchHits
        searchJson(0).obj.get("_source") shouldBe None

      }
      "whitelist mode is used with included blacklisted field" in {
        val searchManager = new SearchManager(basicAuthClient("user1", "pass"))

        val query = ujson.read(
          """
            |{
            |  "_source": "user2"
            |}
            |""".stripMargin
        )

        val result = searchManager.search("testfiltera", query)

        result.responseCode shouldBe 200

        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read("""{}"""))
      }
      "whitelist mode is used with included allowed field but it's wildcard and it's not matched" in {
        val searchManager = new SearchManager(basicAuthClient("user1", "pass"))

        val query = ujson.read(
          """
            |{
            |  "_source": "us*1"
            |}
            |""".stripMargin
        )

        val result = searchManager.search("testfiltera", query)

        result.responseCode shouldBe 200

        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read("""{"user1":"user1Value"}"""))
      }
      "whitelist mode is used with excluded whitelisted field" in {
        val searchManager = new SearchManager(basicAuthClient("user1", "pass"))

        val query = ujson.read(
          """
            |{
            |  "_source": {
            |      "excludes": [ "user1" ]
            |  }
            |}
            |""".stripMargin
        )

        val result = searchManager.search("testfiltera", query)

        result.responseCode shouldBe 200

        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read("""{}"""))
      }
      "whitelist mode with user variable is used " in {
        val searchManager = new SearchManager(basicAuthClient("user2", "pass"))

        val result = searchManager.search("testfiltera")

        result.responseCode shouldBe 200

        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read("""{"user2":"user2Value"}"""))
      }
      "whitelist mode with user variable is used and called by user with 'negated' value" in {
        val searchManager = new SearchManager(basicAuthClient("~user", "pass"))

        val result = searchManager.search("testfiltera")

        result.responseCode shouldBe 200

        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read("""{"~user":"~userValue"}"""))
      }
      "whitelist mode is used with search term query using inaccessible field" in {
        val searchManager = new SearchManager(basicAuthClient("user1", "pass"))

        val query = ujson.read(
          """
            |{
            |  "query": {
            |    "term": {
            |      "counter": 7
            |    }
            |  }
            |}
            |""".stripMargin
        )
        val result = searchManager.search("testfiltera", query)

        result.responseCode shouldBe 200
        result.searchHits.isEmpty shouldBe true
      }
      "whitelist mode is used with search match query using inaccessible field" in {
        val searchManager = new SearchManager(basicAuthClient("user1", "pass"))

        val query = ujson.read(
          """
            |{
            |  "query": {
            |    "match": {
            |      "user2": "user2Value"
            |    }
            |  }
            |}
            |""".stripMargin
        )
        val result = searchManager.search("testfiltera", query)

        result.responseCode shouldBe 200
        result.searchHits.isEmpty shouldBe true
      }
      "whitelist mode with wildcard is used" in {
        val searchManager = new SearchManager(basicAuthClient("user3", "pass"))

        val result = searchManager.search("testfiltera")

        result.responseCode shouldBe 200

        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read("""{"user3":"user3Value"}"""))
      }
      "whitelist mode is used with wildcard and with included blacklisted field" in {
        val searchManager = new SearchManager(basicAuthClient("user2", "pass"))

        val query = ujson.read(
          """
            |{
            |  "_source": {
            |      "includes": [ "us*3" ]
            |  }
            |}
            |""".stripMargin
        )

        val result = searchManager.search("testfiltera", query)

        result.responseCode shouldBe 200

        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read("""{}"""))
      }

      "blacklist mode is used" in {
        val searchManager = new SearchManager(basicAuthClient("user4", "pass"))

        val result = searchManager.search("testfiltera")

        result.responseCode shouldBe 200

        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read(
          """|{
             | "~user": "~userValue",
             | "user1": "user1Value",
             | "user2": "user2Value",
             | "user3": "user3Value",
             | "user5": "user5Value",
             | "user6": "user6Value",
             | "counter": 7
             |}""".stripMargin)
        )
      }
      "blacklist mode with user variable is used " in {
        val searchManager = new SearchManager(basicAuthClient("user5", "pass"))

        val result = searchManager.search("testfiltera")

        result.responseCode shouldBe 200

        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read(
          """|{
             | "~user": "~userValue",
             | "user1": "user1Value",
             | "user2": "user2Value",
             | "user3": "user3Value",
             | "user4": "user4Value",
             | "user6": "user6Value",
             | "counter": 7
             |}""".stripMargin)
        )
      }
      "blacklist mode is used with included blacklisted field search query" in {
        val searchManager = new SearchManager(basicAuthClient("user5", "pass"))

        val query = ujson.read(
          """
            |{
            |  "_source": {
            |      "includes": [ "user5" ]
            |  }
            |}
            |""".stripMargin
        )

        val result = searchManager.search("testfiltera", query)

        result.responseCode shouldBe 200

        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read("""{}"""))
      }
      "blacklist mode is used with excluded whitelisted field search query" in {
        val searchManager = new SearchManager(basicAuthClient("user5", "pass"))

        val query = ujson.read(
          """
            |{
            |  "_source": {
            |      "excludes": [ "*" ]
            |  }
            |}
            |""".stripMargin
        )

        val result = searchManager.search("testfiltera", query)

        result.responseCode shouldBe 200

        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read("""{}"""))
      }
      "blacklist mode with wildcard is used" in {
        val searchManager = new SearchManager(basicAuthClient("user6", "pass"))

        val result = searchManager.search("testfiltera")

        result.responseCode shouldBe 200

        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        source should be(ujson.read(
          """|{
             | "~user": "~userValue",
             | "user1": "user1Value",
             | "user2": "user2Value",
             | "user3": "user3Value",
             | "user4": "user4Value",
             | "user5": "user5Value",
             | "counter": 7
             |}""".stripMargin)
        )
      }
      "docvalue with not-allowed field in search request is used" in {
        val searchManager = new SearchManager(basicAuthClient("user1", "pass"))

        val query = ujson.read(
          """
            |{
            |  "docvalue_fields": ["counter"]
            |}
            |""".stripMargin
        )

        val result = searchManager.search("testfiltera", query)

        result.responseCode shouldBe 200

        val searchJson = result.searchHits
        val source = searchJson(0)("_source")

        searchJson(0).obj.get("fields") should be(None)
        source should be(ujson.read(
          """|{
             | "user1": "user1Value"
             |}""".stripMargin)
        )
      }
    }
    "work for nested fields" when {
      "whitelist mode is used" in {
        val searchManager = new SearchManager(basicAuthClient("user1", "pass"))

        val result = searchManager.search("nestedtest")

        result.responseCode shouldBe 200

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

        val query = ujson.read(
          """
            |{
            |  "_source": {
            |      "includes": [ "secrets.key" ]
            |  }
            |}
            |""".stripMargin
        )

        val result = searchManager.search("nestedtest", query)

        result.responseCode shouldBe 200

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

        val result = searchManager.search("nestedtest")

        result.responseCode shouldBe 200

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

        val result = searchManager.search("nestedtest")

        result.responseCode shouldBe 200

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

        result.responseCode shouldBe 200

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
  }
}

object FieldLevelSecuritySuiteSearchApi {

  def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)

    val doc1 = """{
                 | "~user": "~userValue",
                 | "user1": "user1Value",
                 | "user2": "user2Value",
                 | "user3": "user3Value",
                 | "user4": "user4Value",
                 | "user5": "user5Value",
                 | "user6": "user6Value",
                 | "counter": 7
                 |}""".stripMargin

    val doc2 = """
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

    documentManager.createDoc("testfiltera", 1, ujson.read(doc1)).force()
    documentManager.createDoc("nestedtest", 1, ujson.read(doc2)).force()
  }
}
