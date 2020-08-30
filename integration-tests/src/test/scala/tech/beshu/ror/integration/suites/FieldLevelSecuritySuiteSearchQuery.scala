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

import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait FieldLevelSecuritySuiteSearchQuery
  extends WordSpec
    with BaseSingleNodeEsClusterTest {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/field_level_security_query/readonlyrest.yml"

  override def nodeDataInitializer = Some(FieldLevelSecuritySuiteSearchQuery.nodeDataInitializer())

  protected def assertNoSearchHitsReturnedFor(query: String): Unit

  protected val searchManager = new SearchManager(basicAuthClient("user", "pass"))

  "A fields rule" should {
    "not return any document" when {
      "not allowed fields are used in query (new approach)" which {
        "is term query" in {
          val query =
            """
              |{
              |  "query": {
              |    "term": {
              |      "notAllowedField": 999
              |    }
              |  }
              |}
              |""".stripMargin

          assertNoSearchHitsReturnedFor(query)
        }
        "is match query" in {
          val query =
            """
              |{
              |  "query": {
              |    "match": {
              |      "notAllowedField": 999
              |    }
              |  }
              |}
              |""".stripMargin

          assertNoSearchHitsReturnedFor(query)
        }
        "is bool query with term + match queries" in {
          val query =
            """
              |{
              |  "query": {
              |    "bool": {
              |      "must": [
              |        {"term": {"notAllowedField": 999}},
              |        {"match": {"allowedField": "allowedFieldValue"}}
              |      ]
              |    }
              |  }
              |}
              |""".stripMargin

          assertNoSearchHitsReturnedFor(query)
        }
        "is constant score query" in {
          val query =
            """
              |{
              |  "query": {
              |    "constant_score": {
              |      "filter": {
              |        "term": {"notAllowedField": 999}
              |      }
              |    }
              |  }
              |}
              |""".stripMargin

          assertNoSearchHitsReturnedFor(query)
        }
        "is boosting query" in {
          val query =
            """
              |{
              |  "query": {
              |    "boosting": {
              |      "positive": {
              |        "term": {
              |          "notAllowedField": 999
              |        }
              |      },
              |      "negative": {
              |        "term": {
              |          "allowedField": "allowedFieldValue"
              |        }
              |      },
              |      "negative_boost": 0.5
              |    }
              |  }
              |}
              |""".stripMargin

          assertNoSearchHitsReturnedFor(query)
        }
        "is disjuction max query" in {
          val query =
            """
              |{
              |  "query": {
              |    "dis_max": {
              |      "queries": [
              |        { "term": { "notAllowedField": 999 } },
              |        { "match": { "notAllowedField": 10000 } }
              |      ],
              |      "tie_breaker": 0.7
              |    }
              |  }
              |}
              |""".stripMargin

          assertNoSearchHitsReturnedFor(query)
        }
        "is match bool prefix query" in {
          val query =
            """
              |{
              |  "query": {
              |    "match_bool_prefix": {
              |      "notAllowedTextField": "not allowed"
              |    }
              |  }
              |}
              |""".stripMargin

          assertNoSearchHitsReturnedFor(query)
        }
        "is match phrase query" in {
          val query =
            """
              |{
              |  "query": {
              |    "match_phrase": {
              |      "notAllowedTextField": {
              |        "query": "NOT allowed"
              |      }
              |    }
              |  }
              |}
              |""".stripMargin

          assertNoSearchHitsReturnedFor(query)
        }
        "is match phrase prefix query" in {
          val query =
            """
              |{
              |  "query": {
              |    "match_phrase_prefix": {
              |      "notAllowedTextField": {
              |        "query": "not allowed"
              |      }
              |    }
              |  }
              |}
              |""".stripMargin

          assertNoSearchHitsReturnedFor(query)
        }


        "is common terms query" in {
          val query =
            """
              |{
              |  "query": {
              |    "common": {
              |      "notAllowedTextField": {
              |        "query": "not",
              |        "cutoff_frequency": 0.1
              |      }
              |    }
              |  }
              |}
              |""".stripMargin

          assertNoSearchHitsReturnedFor(query)
        }
      }
    }
  }
}

object FieldLevelSecuritySuiteSearchQuery {

  def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    val document =
      """
        |{
        | "allowedField": "allowedFieldValue",
        | "notAllowedField": 999,
        | "notAllowedTextField": "not allowed text value"
        |}""".stripMargin

    documentManager.createDoc("test-index", 1, ujson.read(document)).force()
  }
}


