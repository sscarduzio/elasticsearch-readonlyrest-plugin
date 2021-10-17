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
import tech.beshu.ror.integration.suites.base.support.{BaseSingleNodeEsClusterTest, SingleClientSupport}
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.ScalaUtils.retry
import tech.beshu.ror.utils.misc.Version

trait FilterRuleSuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with SingleClientSupport {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/filter_rules/readonlyrest.yml"

  override def nodeDataInitializer: Option[ElasticsearchNodeDataInitializer] = Some(FilterRuleSuite.nodeDataInitializer())

  "A filter rule" should {
    "show only doc according to defined filter" when {
      "search api is used" when {
        "custom query in request body is sent" in {
          retry(times = 3) {
            val searchManager = new SearchManager(basicAuthClient("user1", "pass"))
            val result = searchManager.search("test1_index", ujson.read("""{ "query": { "term": { "code": 1 }}}"""))

            result.responseCode shouldBe 200
            result.searchHits.size shouldBe 1

            result.head shouldBe ujson.read("""{"db_name":"db_user1", "code": 1, "status": "ok"}""")
          }
        }
        "there is no query in request body" in {
          retry(times = 3) {
            val searchManager = new SearchManager(basicAuthClient("user1", "pass"))
            val result = searchManager.search("test1_index")

            result.responseCode shouldBe 200
            result.searchHits.size shouldBe 2
            result.docIds should contain allOf("1", "2")

            result.id("1") shouldBe ujson.read("""{"db_name":"db_user1", "code": 1, "status": "ok"}""")
            result.id("2") shouldBe ujson.read("""{"db_name":"db_user1", "code": 2, "status": "ok"}""")
          }
        }
        "wildcard in filter query is used" in {
          retry(times = 3) {
            val searchManager = new SearchManager(basicAuthClient("user2", "pass"))
            val result = searchManager.search("test1_index")

            result.responseCode shouldBe 200
            result.searchHits.size shouldBe 1

            result.head shouldBe ujson.read("""{"db_name":"db_user2", "code": 1, "status": "ok"}""")
          }
        }
        "prefix in filter query is used" in {
          retry(times = 3) {
            val searchManager = new SearchManager(basicAuthClient("user3", "pass"))
            val result = searchManager.search("test1_index")

            result.responseCode shouldBe 200
            result.searchHits.size shouldBe 1

            result.head shouldBe ujson.read("""{"db_name":"db_user3", "code": 2, "status": "wrong"}""")
          }
        }
      }
      "msearch api is used" in {
        retry(times = 3) {
          val searchManager = new SearchManager(basicAuthClient("user1", "pass"))
          val matchAllIndicesQuery = Seq(
            """{"index":"*"}""",
            """{"query" : {"match_all" : {}}}""")
          val result = searchManager.mSearchUnsafe(matchAllIndicesQuery: _*)

          result.responseCode shouldBe 200
          result.responses.size shouldBe 1
          result.searchHitsForResponse(0).size shouldBe 2
        }
      }
      "get api is used" when {
        "document is accessible" in {
          retry(times = 3) {
            val documentManager = new DocumentManager(basicAuthClient("user1", "pass"), esVersionUsed)
            val result = documentManager.get("test1_index", 1)

            result.responseCode shouldBe 200
            result.responseJson("found").bool shouldBe true
            result.responseJson("_source") shouldBe ujson.read("""{"db_name":"db_user1", "code": 1, "status": "ok"}""")
          }
        }
        "index is inaccessible" in {
          retry(times = 3) {
            val documentManager = new DocumentManager(basicAuthClient("user1", "pass"), esVersionUsed)
            val result = documentManager.get("test2_index", 1)

            result.responseCode shouldBe 401
          }
        }
        "index is not found" in {
          retry(times = 3) {
            val documentManager = new DocumentManager(rorAdminClient, esVersionUsed)
            val result = documentManager.get("test3_index", 1)

            result.responseCode shouldBe 404
          }
        }
        "document is filtered and inaccessible" in {
          retry(times = 3) {
            val documentManager = new DocumentManager(basicAuthClient("user1", "pass"), esVersionUsed)
            val result = documentManager.get("test1_index", 4)

            result.responseCode shouldBe 404
            result.responseJson("found").bool shouldBe false
          }
        }
      }
      "mget api is used" when {
        "both requested documents are accessible" in {
          retry(times = 3) {
            val documentManager = new DocumentManager(basicAuthClient("user1", "pass"), esVersionUsed)
            val result = documentManager.mGet(
              ujson.read(
                """{
                  |  "docs":[
                  |    {
                  |      "_index":"test1_index",
                  |      "_id":1
                  |    },
                  |    {
                  |      "_index":"test1_index",
                  |      "_id":2
                  |    }
                  |  ]
                  |}""".stripMargin
              )
            )

            result.responseCode shouldBe 200

            result.docs(0)("found").bool shouldBe true
            result.docs(0)("_id").str shouldBe "1"
            result.docs(0)("_source") shouldBe ujson.read("""{"db_name":"db_user1", "code": 1, "status": "ok"}""")

            result.docs(1)("found").bool shouldBe true
            result.docs(1)("_id").str shouldBe "2"
            result.docs(1)("_source") shouldBe ujson.read("""{"db_name":"db_user1", "code": 2, "status": "ok"}""")
          }
        }
        "one of requested documents is filtered and inaccessible" in {
          retry(times = 3) {
            val documentManager = new DocumentManager(basicAuthClient("user1", "pass"), esVersionUsed)
            val result = documentManager.mGet(
              ujson.read(
                """{
                  |  "docs":[
                  |    {
                  |      "_index":"test1_index",
                  |      "_id":1
                  |    },
                  |    {
                  |      "_index":"test1_index",
                  |      "_id":3
                  |    }
                  |  ]
                  |}""".stripMargin
              )
            )

            result.responseCode shouldBe 200

            result.docs(0)("found").bool shouldBe true
            result.docs(0)("_id").str shouldBe "1"
            result.docs(0)("_source") shouldBe ujson.read("""{"db_name":"db_user1", "code": 1, "status": "ok"}""")

            result.docs(1)("found").bool shouldBe false
            result.docs(1)("_id").str shouldBe "3"
          }
        }
        "one of requested documents is filtered and inaccessible and second is nonexistent" in {
          retry(times = 3) {
            val documentManager = new DocumentManager(basicAuthClient("user1", "pass"), esVersionUsed)
            val result = documentManager.mGet(
              ujson.read(
                """{
                  |  "docs":[
                  |    {
                  |      "_index":"test1_index",
                  |      "_id":100
                  |    },
                  |    {
                  |      "_index":"test1_index",
                  |      "_id":3
                  |    }
                  |  ]
                  |}""".stripMargin
              )
            )

            result.responseCode shouldBe 200

            result.docs(0)("found").bool shouldBe false
            result.docs(0)("_id").str shouldBe "100"

            result.docs(1)("found").bool shouldBe false
            result.docs(1)("_id").str shouldBe "3"
          }
        }
        "both requested documents are filtered and inaccessible" in {
          retry(times = 3) {
            val documentManager = new DocumentManager(basicAuthClient("user1", "pass"), esVersionUsed)
            val result = documentManager.mGet(
              ujson.read(
                """{
                  |  "docs":[
                  |    {
                  |      "_index":"test1_index",
                  |      "_id":3
                  |    },
                  |    {
                  |      "_index":"test1_index",
                  |      "_id":4
                  |    }
                  |  ]
                  |}""".stripMargin
              )
            )

            result.responseCode shouldBe 200

            result.docs(0)("found").bool shouldBe false
            result.docs(0)("_id").str shouldBe "3"

            result.docs(1)("found").bool shouldBe false
            result.docs(1)("_id").str shouldBe "4"
          }
        }
        "both requested documents are nonexistent" in {
          retry(times = 3) {
            val documentManager = new DocumentManager(basicAuthClient("user1", "pass"), esVersionUsed)
            val result = documentManager.mGet(
              ujson.read(
                """{
                  |  "docs":[
                  |    {
                  |      "_index":"test1_index",
                  |      "_id":300
                  |    },
                  |    {
                  |      "_index":"test1_index",
                  |      "_id":400
                  |    }
                  |  ]
                  |}""".stripMargin
              )
            )
            result.responseCode shouldBe 200

            result.docs(0)("found").bool shouldBe false
            result.docs(0)("_id").str shouldBe "300"

            result.docs(1)("found").bool shouldBe false
            result.docs(1)("_id").str shouldBe "400"
          }
        }
      }
    }
    "not allow request" when {
      "request is not read only" in {
        val documentManager = new DocumentManager(basicAuthClient("user1", "pass"), esVersionUsed)
        val result = documentManager.createDoc("test1_index", 5, ujson.read("""{"db_name":"db_user4", "code": 2}"""))

        result.responseCode shouldBe 401
      }
      "search request has 'profile' option" in {
        val searchManager = new SearchManager(basicAuthClient("user1", "pass"))
        val result = searchManager.search("test1_index", ujson.read("""{ "query": { "term": { "code": 1 }}, "profile": true}"""))

        result.responseCode shouldBe 401
      }
      "search request has suggestions" in {
        val searchManager = new SearchManager(basicAuthClient("user1", "pass"))
        val query = ujson.read(
          """|{
             |"query": { "term": { "code": 1 }},
             |"suggest": {
             |  "my-suggest-1" : {
             |    "text" : "something",
             |    "term" : {
             |      "field" : "db_name"
             |    }
             |  }
             | }
             |}""".stripMargin
        )
        val result = searchManager.search("test1_index", query)

        result.responseCode shouldBe 401
      }
      "return error" when {
        "filter query is malformed" in {
          val searchManager = new SearchManager(basicAuthClient("user4", "pass"))
          val result = searchManager.search("test1_index", ujson.read("""{ "query": { "term": { "code": 1 }}}"""))

          if (Version.greaterOrEqualThan(esVersionUsed, 7, 6, 0)) {
            result.responseCode shouldBe 400
          } else {
            result.responseCode shouldBe 500
          }
        }
      }
    }
  }
}

object FilterRuleSuite {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)

    documentManager.createDoc("test1_index", 1, ujson.read("""{"db_name":"db_user1", "code": 1, "status": "ok"}""")).force()
    documentManager.createDoc("test1_index", 2, ujson.read("""{"db_name":"db_user1", "code": 2, "status": "ok"}""")).force()
    documentManager.createDoc("test1_index", 3, ujson.read("""{"db_name":"db_user2", "code": 1, "status": "ok"}""")).force()
    documentManager.createDoc("test1_index", 4, ujson.read("""{"db_name":"db_user3", "code": 2, "status": "wrong"}""")).force()

    documentManager.createDoc("test2_index", 1, ujson.read("""{"db_name":"db_user1", "code": 1, "status": "ok"}""")).force()
    documentManager.createDoc("test2_index", 2, ujson.read("""{"db_name":"db_user1", "code": 2, "status": "ok"}""")).force()
  }
}
