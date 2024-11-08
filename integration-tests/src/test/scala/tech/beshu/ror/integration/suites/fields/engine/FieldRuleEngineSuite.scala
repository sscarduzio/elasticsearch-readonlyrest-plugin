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
package tech.beshu.ror.integration.suites.fields.engine

import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.ESVersionSupportForAnyWordSpecLike
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsClusterProvider}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.{CustomScalaTestMatchers, Version}

import scala.concurrent.duration.*
import scala.language.postfixOps

trait FieldRuleEngineSuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with ESVersionSupportForAnyWordSpecLike
    with BeforeAndAfterAll 
    with CustomScalaTestMatchers {
  this: EsClusterProvider =>

  override def nodeDataInitializer: Option[ElasticsearchNodeDataInitializer] = Some {
    FieldRuleEngineSuite.nodeDataInitializer()
  }

  protected lazy val user1SearchManager = new SearchManager(basicAuthClient("user1", "pass"), esVersionUsed)
  protected lazy val user2SearchManager = new SearchManager(basicAuthClient("user2", "pass"), esVersionUsed)

  protected lazy val user3DocumentManager = new DocumentManager(basicAuthClient("user3", "pass"), esVersionUsed)
  protected lazy val user4DocumentManager = new DocumentManager(basicAuthClient("user4", "pass"), esVersionUsed)

  "Search request with field rule defined" when {
    "specific FLS engine is used" should {
      "match and return filtered document source" when {
        "modifiable at ES level query using not allowed field is passed in request" in {
          val result = user1SearchManager.search(
            "test-index",
            ujson.read(
              """
                |{
                |  "query": {
                |    "match": {
                |       "notAllowedField": 1
                |    }
                |  }
                |}
                |""".stripMargin)
          )

          result should have statusCode 200
          result.searchHits shouldBe List.empty
        }
      }
      "handle unmodifiable at ES level query" when {
        "using not allowed field is passed in request" in {
          val result = user1SearchManager.search(
            "test-index",
            ujson.read(
              """
                |{
                |  "query": {
                |    "query_string": {
                |      "query": "notAllowed\\*: 1"
                |    }
                |  }
                |}
                |""".stripMargin)
          )

          unmodifiableQueryAssertion(result)
        }
      }
      "properly handle forbidden field in the aggregate" in {
        val result = user1SearchManager.search(
          "test-index",
          ujson.read(
            s"""
               |{
               |  "aggs":{
               |    "my_aggregate":{
               |      "terms":{
               |        "field":"forbiddenField"
               |      }
               |    }
               |  }
               |}
             """.stripMargin)
        )

        result should have statusCode 200
        result.searchHits.size shouldBe 5

        val aggregateName = "my_aggregate"
        result.aggregations shouldBe Map(
          aggregateName -> ujson.read(
            """{
              |  "doc_count_error_upper_bound": 0,
              |  "sum_other_doc_count": 0,
              |  "buckets": []
              |}""".stripMargin
          )
        )
      }
    }
  }

  "Scroll search" should {
    "properly handle allowed fields" in {
      val result = user1SearchManager.searchScroll(
        size = 3,
        scroll = 1 minute,
        "test-index"
      )
      scrollSearchShouldProperlyHandleAllowedFields(result)
    }
    "properly handle forbidden fields" in {
      val result = user2SearchManager.searchScroll(
        size = 3,
        scroll = 1 minute,
        "test-index"
      )
      scrollSearchShouldProperlyHandleForbiddenFields(result)
    }
  }

  "Get document" should {
    "properly handle allowed fields" in {
      val result = user3DocumentManager.get(index = "test-index", id = 3)

      result should have statusCode 200
      result.responseJson("_source") should be (
        ujson.read(s"""{"allowedField":"allowed:3"}""")
      )
    }
    "properly handle forbidden fields" in {
      val result = user4DocumentManager.get(index = "test-index", id = 3)

      result should have statusCode 200
      result.responseJson("_source") should be(
        ujson.read(s"""{"allowedField":"allowed:3","forbiddenField":3}"""),
      )
    }
  }

  protected def unmodifiableQueryAssertion(searchResult: SearchManager#SearchResult): Unit

  protected def scrollSearchShouldProperlyHandleAllowedFields(searchResult: SearchManager#SearchResult): Unit = {
    searchResult should have statusCode 200
    searchResult.searchHits.map(h => h.obj("_source")) should contain theSameElementsAs Set(
      ujson.read(s"""{"allowedField": "allowed:1"}"""),
      ujson.read(s"""{"allowedField": "allowed:2"}"""),
      ujson.read(s"""{"allowedField": "allowed:3"}"""),
    )

    val result2 = user1SearchManager.searchScroll(searchResult.scrollId)
    result2 should have statusCode 200
    result2.searchHits.map(h => h.obj("_source")) should contain theSameElementsAs Set(
      ujson.read(s"""{"allowedField": "allowed:4"}"""),
      ujson.read(s"""{"allowedField": "allowed:5"}"""),
    )

    val result3 = user1SearchManager.searchScroll(searchResult.scrollId)
    if(Version.greaterOrEqualThan(esVersionUsed, 7, 0, 0)) {
      result3 should have statusCode 404
    } else {
      result3 should have statusCode 200
      result3.searchHits shouldBe (List.empty)
    }
  }

  protected def scrollSearchShouldProperlyHandleForbiddenFields(searchResult: SearchManager#SearchResult): Unit = {
    searchResult should have statusCode 200
    searchResult.searchHits.map(h => h.obj("_source")) should contain theSameElementsAs Set(
      ujson.read(s"""{"allowedField": "allowed:1", "forbiddenField":1}"""),
      ujson.read(s"""{"allowedField": "allowed:2", "forbiddenField":2}"""),
      ujson.read(s"""{"allowedField": "allowed:3", "forbiddenField":3}"""),
    )

    val result2 = user2SearchManager.searchScroll(searchResult.scrollId)
    result2 should have statusCode 200
    result2.searchHits.map(h => h.obj("_source")) should contain theSameElementsAs Set(
      ujson.read(s"""{"allowedField": "allowed:4", "forbiddenField":4}"""),
      ujson.read(s"""{"allowedField": "allowed:5", "forbiddenField":5}"""),
    )

    val result3 = user2SearchManager.searchScroll(searchResult.scrollId)
    if(Version.greaterOrEqualThan(esVersionUsed, 7, 0, 0)) {
      result3 should have statusCode 404
    } else {
      result3 should have statusCode 200
      result3.searchHits shouldBe (List.empty)
    }
  }
}

object FieldRuleEngineSuite {

  def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    def createDocument(id: Int) = ujson.read {
      s"""
        |{
        | "allowedField": "allowed:$id",
        | "notAllowedField": $id,
        | "forbiddenField": $id
        |}""".stripMargin
      }

    (1 to 5).foreach { idx =>
      documentManager.createDoc("test-index", idx, createDocument(idx)).force()
    }
  }
}