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

trait FieldRuleEngineSuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with ESVersionSupportForAnyWordSpecLike
    with BeforeAndAfterAll {
  this: EsClusterProvider =>

  override def nodeDataInitializer: Option[ElasticsearchNodeDataInitializer] = Some {
    FieldRuleEngineSuite.nodeDataInitializer()
  }

  private lazy val searchManager = new SearchManager(basicAuthClient("user", "pass"))

  "Search request with field rule defined" when {
    "specific FLS engine is used" should {
      "match and return filtered document source" when {
        "modifiable at ES level query using not allowed field is passed in request" in {
          val result = searchManager.search(
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

          result.responseCode shouldBe 200
          result.searchHits shouldBe List.empty
        }
      }
      "handle unmodifiable at ES level query" when {
        "using not allowed field is passed in request" in {
          val result = searchManager.search(
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
        val result = searchManager.search(
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

        result.responseCode shouldBe 200
        result.searchHits.size shouldBe 5
        val aggregateName =
          if(executedOn(rorProxy)) "sterms#my_aggregate" // this is weird and probably we need to fix it. Currently, there is no time for this
          else "my_aggregate"
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

  protected def unmodifiableQueryAssertion(searchResult: SearchManager.SearchResult): Unit
}

object FieldRuleEngineSuite {

  def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    val document =
      """
        |{
        | "allowedField": "allowedFieldValue",
        | "notAllowedField": 1,
        | "forbiddenField": 1
        |}""".stripMargin

    documentManager.createDoc("test-index", 1, ujson.read(document)).force()
    documentManager.createDoc("test-index", 2, ujson.read(document)).force()
    documentManager.createDoc("test-index", 3, ujson.read(document)).force()
    documentManager.createDoc("test-index", 4, ujson.read(document)).force()
    documentManager.createDoc("test-index", 5, ujson.read(document)).force()
  }
}