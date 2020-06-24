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

import org.scalatest.concurrent.{Eventually, IntegrationPatience}
import org.scalatest.{Matchers, WordSpec}
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{DocumentManagerJ, IndexManagerJ, SearchManagerJ}
import tech.beshu.ror.utils.httpclient.RestClient

import scala.collection.JavaConverters._

//TODO change test names. Current names are copies from old java integration tests
trait IndicesAliasesSuite
  extends WordSpec
    with BaseSingleNodeEsClusterTest
    with Eventually
    with IntegrationPatience
    with Matchers {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/indices_aliases_test/readonlyrest.yml"

  override def nodeDataInitializer = Some(IndicesAliasesSuite.nodeDataInitializer())

  private lazy val restrictedDevSearchManager = new SearchManagerJ(basicAuthClient("restricted", "dev"))
  private lazy val unrestrictedDevSearchManager = new SearchManagerJ(basicAuthClient("unrestricted", "dev"))
  private lazy val adminIndexManager = new IndexManagerJ(adminClient)
  private lazy val perfmonIndexManager = new IndexManagerJ(basicAuthClient("perfmon", "dev"))
  private lazy val vietMyanSearchManager = new SearchManagerJ(basicAuthClient("VIET_MYAN", "dev"))

  "testDirectIndexQuery" in {
    eventually {
      val response = unrestrictedDevSearchManager.search("/my_data/_search")

      response.getResponseCode should be(200)
      response.getSearchHits.size() should be(2)
    }
  }
  "testAliasQuery" in {
    eventually {
      val response = unrestrictedDevSearchManager.search("/public_data/_search")

      response.getResponseCode should be(200)
      response.getSearchHits.size() should be(1)
    }
  }
  "testAliasAsWildcard" in {
    eventually {
      val response = unrestrictedDevSearchManager.search("/pub*/_search")

      response.getResponseCode should be(200)
      response.getSearchHits.size() should be(1)
    }
  }

  // Tests with indices rule restricting to "pub*"

  "testRestrictedPureIndex" in {
    eventually {
      val response = restrictedDevSearchManager.search("/my_data/_search")

      response.getResponseCode should be(404)
    }
  }
  "testRestrictedAlias" in {
    eventually {
      val response = restrictedDevSearchManager.search("/public_data/_search")

      response.getResponseCode should be(200)
      response.getSearchHits.size() should be(1)
    }
  }
  "testRestrictedAliasAsWildcard" in {
    eventually {
      val response = restrictedDevSearchManager.search("/public*/_search")

      response.getResponseCode should be(200)
      response.getSearchHits.size() should be(1)
    }
  }
  "testRestrictedAliasAsHalfWildcard" in {
    eventually {
      val response = restrictedDevSearchManager.search("/pu*/_search")

      response.getResponseCode should be(200)
      response.getSearchHits.size() should be(1)
    }
  }

  // real cases from github

  "testIndexCanBeAccessedByAdminUsingNameOrAlias" in {
    eventually {
      val firstResponse = adminIndexManager.get("blabla")

      firstResponse.getResponseCode should be(200)
      firstResponse.getAliases.size() should be(1)

      val secondResponse = adminIndexManager.get("perfmon_my_test_alias")

      secondResponse.getResponseCode should be(200)
      secondResponse.getAliases.size() should be(1)
    }
  }
  "testIndexCanBeAccessedByAdminUsingNameOrAliasWithWildcard" in {
    eventually {
      val firstResponse = adminIndexManager.get("bla*")

      firstResponse.getResponseCode should be(200)
      firstResponse.getAliases.size() should be(1)

      val secondResponse = adminIndexManager.get("perf*mon_my_test*")

      secondResponse.getResponseCode should be(200)
      secondResponse.getAliases.size() should be(1)
    }
  }
  "testIndexCanBeAccessedByUserPerfmonUsingNameOrAlias" in {
    eventually {
      val firstResponse = perfmonIndexManager.get("blabla")

      firstResponse.getResponseCode should be(200)
      firstResponse.getAliases.size() should be(1)

      val secondResponse = perfmonIndexManager.get("perfmon_my_test_alias")

      secondResponse.getResponseCode should be(200)
      secondResponse.getAliases.size() should be(1)
    }
  }
  "testIndexCanBeAccessedByUserPerfmonUsingNameOrAliasWithWildcard" in {
    eventually {
      val firstResponse = perfmonIndexManager.get("bla*")

      firstResponse.getResponseCode should be(200)
      firstResponse.getAliases.size() should be(1)

      val secondResponse = perfmonIndexManager.get("perf*mon_my_test*")

      secondResponse.getResponseCode should be(200)
      secondResponse.getAliases.size() should be(1)
    }
  }
  "testVietMianUserShouldBeAbleToAccessVietnamIndex" in {
    eventually {
      val response = vietMyanSearchManager.search("/vuln-ass-all-vietnam/_search")

      response.getResponseCode should be(200)
      response.getSearchHits.size() should be(1)
    }
  }
  "testVietMianUserShouldNotBeAbleToAccessCongoIndex" in {
    eventually {
      val response = vietMyanSearchManager.search("/vuln-ass-all-congo/_search")

      response.getResponseCode should be(404)
      response.getSearchHits.size() should be(0)
    }
  }
  "testVietMianUserShouldBeAbleToSeeAllowedIndicesUsingAlias" in {
    eventually {
      val response = vietMyanSearchManager.search("/all-subs-data/_search")

      response.getResponseCode should be(200)
      response.getSearchHits.size() should be(2)
    }
  }
}

object IndicesAliasesSuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    val documentManager = new DocumentManagerJ(adminRestClient)
    documentManager.insertDoc("/my_data/test/1", """{"hello":"world"}""")
    documentManager.insertDoc("/my_data/test/2", """{"hello":"there", "public":1}""")
    documentManager.insertDoc("/my_data/_alias/public_data", """{"filter":{"term":{"public":1}}}""")
    documentManager.insertDoc("/blabla", """{"aliases" : {"perfmon_my_test_alias":{}}}""")
    documentManager.insertDoc("/vuln-ass-all-angola/test/1", """{"country":"angola"}""")
    documentManager.insertDoc("/vuln-ass-all-china/test/1", """{"country":"china"}""")
    documentManager.insertDoc("/vuln-ass-all-congo/test/1", """{"country":"congo"}""")
    documentManager.insertDoc("/vuln-ass-all-myanmar/test/1", """{"country":"myanmar"}""")
    documentManager.insertDoc("/vuln-ass-all-norge/test/1", """{"country":"norge"}""")
    documentManager.insertDoc("/vuln-ass-all-vietnam/test/1", """{"country":"vietnam"}""")
    documentManager.createAlias("all-subs-data", Set("vuln-ass-all-angola", "vuln-ass-all-china", "vuln-ass-all-congo", "vuln-ass-all-myanmar", "vuln-ass-all-norge", "vuln-ass-all-vietnam").asJava)
  }
}