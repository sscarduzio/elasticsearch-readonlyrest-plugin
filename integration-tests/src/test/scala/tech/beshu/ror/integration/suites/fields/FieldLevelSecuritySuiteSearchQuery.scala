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

trait FieldLevelSecuritySuiteSearchQuery
  extends WordSpec
    with BaseSingleNodeEsClusterTest {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/field_level_security_query/readonlyrest.yml"

  override def nodeDataInitializer = Some(FieldLevelSecuritySuiteSearchQuery.nodeDataInitializer())

  protected def assertNoSearchHitsReturnedFor(query: String): Unit

  protected val searchManager = new SearchManager(basicAuthClient("user", "pass"))

  "Search action" should {
    "not return any document" when {
      "not allowed by rule fields are used in query (new approach)" which {
        "is 'term' query" in {
          val query =
            """
              |{
              |  "query": {
              |    "term": {
              |      "notAllowedField": 1
              |    }
              |  }
              |}
              |""".stripMargin

          assertNoSearchHitsReturnedFor(query)
        }
        "is 'match' query" in {
          val query =
            """
              |{
              |  "query": {
              |    "match": {
              |      "notAllowedField": 1
              |    }
              |  }
              |}
              |""".stripMargin

          assertNoSearchHitsReturnedFor(query)
        }
        "is 'bool' query with term + match queries" in {
          val query =
            """
              |{
              |  "query": {
              |    "bool": {
              |      "must": [
              |        {"term": {"notAllowedField": 1}},
              |        {"match": {"allowedField": "allowedFieldValue"}}
              |      ]
              |    }
              |  }
              |}
              |""".stripMargin

          assertNoSearchHitsReturnedFor(query)
        }
        "is 'constant score' query" in {
          val query =
            """
              |{
              |  "query": {
              |    "constant_score": {
              |      "filter": {
              |        "term": {"notAllowedField": 1}
              |      }
              |    }
              |  }
              |}
              |""".stripMargin

          assertNoSearchHitsReturnedFor(query)
        }
        "is 'boosting' query" in {
          val query =
            """
              |{
              |  "query": {
              |    "boosting": {
              |      "positive": {
              |        "term": {
              |          "notAllowedField": 1
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
        "is 'disjuction max' query" in {
          val query =
            """
              |{
              |  "query": {
              |    "dis_max": {
              |      "queries": [
              |        { "term": { "notAllowedField": 1 } },
              |        { "match": { "notAllowedField": 10000 } }
              |      ],
              |      "tie_breaker": 0.7
              |    }
              |  }
              |}
              |""".stripMargin

          assertNoSearchHitsReturnedFor(query)
        }
        "is 'match bool prefix' query" in {
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
        "is 'match phrase' query" in {
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
        "is 'match phrase prefix' query" in {
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
        "is 'common terms' query" in {
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

        //no wildcard!!
        "is 'exists' query" in {
          val query =
            """
              |{
              |  "query": {
              |    "exists": {
              |      "field": "notAllowedField"
              |    }
              |  }
              |}
              |""".stripMargin

          assertNoSearchHitsReturnedFor(query)

        }
        "is 'fuzzy' query" in {
          val query =
            """
              |{
              |  "query": {
              |    "fuzzy": {
              |      "notAllowedTextField": {
              |        "value": "not"
              |      }
              |    }
              |  }
              |}
              |""".stripMargin

          assertNoSearchHitsReturnedFor(query)
        }

        "is 'prefix' query" in {
          val query =
            """
              |{
              |  "query": {
              |    "prefix": {
              |      "notAllowedTextField": {
              |        "value": "not"
              |      }
              |    }
              |  }
              |}
              |""".stripMargin

          assertNoSearchHitsReturnedFor(query)
        }
        "is 'range' query" in {
          val query =
            """
              |{
              |  "query": {
              |    "range": {
              |      "notAllowedField": {
              |        "gte": 10,
              |        "lte": 1000
              |      }
              |    }
              |  }
              |}
              |""".stripMargin

          assertNoSearchHitsReturnedFor(query)
        }
        "is 'regexp' query" in {
          val query =
            """
              |{
              |  "query": {
              |    "regexp": {
              |      "notAllowedTextField": {
              |        "value": "no.*"
              |      }
              |    }
              |  }
              |}
              |""".stripMargin

          assertNoSearchHitsReturnedFor(query)
        }
        "is 'terms set' query" in {
          val query =
            """
              |{
              |  "query": {
              |    "terms_set": {
              |      "notAllowedTextField": {
              |        "terms": [ "not", "allowed" ],
              |        "minimum_should_match_field": "notAllowedField"
              |      }
              |    }
              |  }
              |}
              |""".stripMargin

          assertNoSearchHitsReturnedFor(query)
        }
        "is 'wildcard' query" in {
          val query =
            """
              |{
              |  "query": {
              |    "wildcard": {
              |      "notAllowedTextField": {
              |        "value": "not*"
              |      }
              |    }
              |  }
              |}
              |""".stripMargin

          assertNoSearchHitsReturnedFor(query)
        }
      }
      "not allowed fields are used (old approach with context) header" when {
        "is 'exists' query with wildcard" in {
          val query =
            """
              |{
              |  "query": {
              |    "exists": {
              |      "field": "notAllowed*ld"
              |    }
              |  }
              |}
              |""".stripMargin

          assertNoSearchHitsReturnedFor(query)
        }
        "is 'query_string' query with wildcard" in {
          val query =
            """
              |{
              |  "query": {
              |    "query_string": {
              |      "query": "notAllowed\\*:(not allowed*) OR 1"
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
        | "notAllowedField": 1,
        | "notAllowedTextField": "not allowed text value"
        |}""".stripMargin

    documentManager.createDoc("test-index", 1, ujson.read(document)).force()
  }
}


