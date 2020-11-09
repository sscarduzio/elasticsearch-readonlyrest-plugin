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

import org.scalatest.Matchers._
import org.scalatest.{Assertion, WordSpec}
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.ESVersionSupport
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait FieldRuleEngineSuite
  extends WordSpec
    with BaseSingleNodeEsClusterTest
    with ESVersionSupport {
  this: EsContainerCreator =>

  import FieldRuleEngineSuite.QueriesUsingNotAllowedField._


  override def nodeDataInitializer = Some(FieldRuleEngineSuite.nodeDataInitializer())

  protected def unmodifableQueryAssertion(user: String, query: String): Assertion

  "Search request with field rule defined" when {
    "specifc FLS engine is used" should {
      "match and return filtered document source" when {
        "modifiable at ES level query using not allowed field is passed in request" in {
          assertNoSearchHitsReturnedFor("user", modifiableAtEsLevelQuery)
        }
      }
      "handle unmodifiable at ES level query" when {
        "using not allowed field is passed in request" in {
          unmodifableQueryAssertion("user", unmodifiableAtEsLevelQuery)
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

object FieldRuleEngineSuite {

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