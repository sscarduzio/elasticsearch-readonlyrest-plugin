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
package tech.beshu.ror.integration.suites.fields.mode

import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.ESVersionSupport
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait FieldRuleModeSuite
  extends WordSpec
    with BaseSingleNodeEsClusterTest
    with ESVersionSupport {
  this: EsContainerCreator =>

  import FieldRuleModeSuite.QueriesUsingNotAllowedField._

  override implicit val rorConfigFileName = "/field_level_security_mode/readonlyrest.yml"

  override def nodeDataInitializer = Some(FieldRuleModeSuite.nodeDataInitializer())

  "Search request with field rule defined" when {
    "default (hybrid) FLS mode is used"  should {
      "match and return filtered document source" when {
        "modifiable at ES level query using not allowed field is passed in request" in {
          assertNoSearchHitsReturnedFor("user1", modifiableAtEsLevelQuery)
        }
        "unmodifiable at ES level query using not allowed field is passed in request (fallback to lucene)" excludeES "proxy" in {
          assertNoSearchHitsReturnedFor("user1", unmodifiableAtEsLevelQuery)
        }
      }
    }
    "explicit 'hybrid' FLS mode is used" should {
      "match and return filtered document source" when {
        "modifiable at ES level query using not allowed field is passed in request" in {
          assertNoSearchHitsReturnedFor("user2", modifiableAtEsLevelQuery)
        }
        "unmodifiable at ES level query using not allowed field is passed in request (fallback to lucene)" excludeES "proxy" in {
          assertNoSearchHitsReturnedFor("user2", unmodifiableAtEsLevelQuery)
        }
      }
    }
    "explicit 'legacy' FLS mode is used" should {
      "match and return filtered document source with fallback to lucene regardless of passed query" when {
        "modifiable at ES level query using not allowed field is passed in request" excludeES "proxy" in {
          assertNoSearchHitsReturnedFor("user3", modifiableAtEsLevelQuery)
        }
        "unmodifiable at ES level query using not allowed field is passed in request" excludeES "proxy" in {
          assertNoSearchHitsReturnedFor("user3", unmodifiableAtEsLevelQuery)
        }
      }
    }
    "explicit 'proxy' FLS mode is used" should {
      "match and return filtered document source" when {
        "modifiable at ES level query using not allowed field is passed in request" in {
          assertNoSearchHitsReturnedFor("user4", modifiableAtEsLevelQuery)
        }
      }
      "not match" when {
        "unmodifiable at ES level query using not allowed field is passed in request" in {
          assertOperationNotAllowed("user4", unmodifiableAtEsLevelQuery)
        }
      }
    }
  }

  def assertNoSearchHitsReturnedFor(user: String, passedQuery: String) = {
    val searchManager = new SearchManager(basicAuthClient(user, "pass"))
    val result = searchManager.search("test-index", ujson.read(passedQuery))

    result.responseCode shouldBe 200
    result.searchHits.isEmpty shouldBe true
  }

  def assertOperationNotAllowed(user: String, passedQuery: String) = {
    val searchManager = new SearchManager(basicAuthClient(user, "pass"))
    val result = searchManager.search("test-index", ujson.read(passedQuery))
    result.responseCode shouldBe 401
  }
}

object FieldRuleModeSuite {

  object QueriesUsingNotAllowedField {
    val modifiableAtEsLevelQuery =
      """
        |{
        |  "query": {
        |    "match": {
        |       "notAllowedField": 1
        |    }
        |  }
        |}
        |""".stripMargin

    val unmodifiableAtEsLevelQuery =
      """
        |{
        |  "query": {
        |    "query_string": {
        |      "query": "notAllowed\\*: 1"
        |    }
        |  }
        |}
        |""".stripMargin
  }

  def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    val document =
      """
        |{
        | "allowedField": "allowedFieldValue",
        | "notAllowedField": 1
        |}""".stripMargin

    documentManager.createDoc("test-index", 1, ujson.read(document)).force()
  }
}