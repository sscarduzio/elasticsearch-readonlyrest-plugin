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
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.ESVersionSupportForAnyWordSpecLike
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.IndexManager.AliasAction
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, IndexManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient

//TODO change test names. Current names are copies from old java integration tests
trait SearchWithAliasesSuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with ESVersionSupportForAnyWordSpecLike
    with Eventually
    with IntegrationPatience
    with Matchers {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/indices_aliases_test/readonlyrest.yml"

  override def nodeDataInitializer = Some(SearchWithAliasesSuite.nodeDataInitializer())

  private lazy val restrictedDevSearchManager = new SearchManager(basicAuthClient("restricted", "dev"))
  private lazy val unrestrictedDevSearchManager = new SearchManager(basicAuthClient("unrestricted", "dev"))
  private lazy val adminIndexManager = new IndexManager(adminClient, esVersionUsed)
  private lazy val perfmonIndexManager = new IndexManager(basicAuthClient("perfmon", "dev"), esVersionUsed)
  private lazy val vietMyanSearchManager = new SearchManager(basicAuthClient("VIET_MYAN", "dev"))

  "testDirectIndexQuery" in eventually {
    val response = unrestrictedDevSearchManager.search("my_data")

    response.responseCode should be(200)
    response.searchHits.size should be(2)
  }
  "testAliasQuery" in {
    val response = unrestrictedDevSearchManager.search("public_data")

    response.responseCode should be(200)
    response.searchHits.size should be(1)
  }
  "testAliasAsWildcard" in {
    val response = unrestrictedDevSearchManager.search("pub*")

    response.responseCode should be(200)
    response.searchHits.size should be(1)
  }

  // Tests with indices rule restricting to "pub*"
  "testRestrictedPureIndex" in {
    val response = restrictedDevSearchManager.search("my_data")

    response.responseCode should be(404)
  }
  "testRestrictedAlias" in {
    val response = restrictedDevSearchManager.search("public_data")

    response.responseCode should be(200)
    response.searchHits.size should be(1)
  }
  "testRestrictedAliasAsWildcard" in {
    val response = restrictedDevSearchManager.search("public*")

    response.responseCode should be(200)
    response.searchHits.size should be(1)
  }
  "testRestrictedAliasAsHalfWildcard" in {
    val response = restrictedDevSearchManager.search("pu*")

    response.responseCode should be(200)
    response.searchHits.size should be(1)
  }

  // real cases from github
  "testIndexCanBeAccessedByAdminUsingNameOrAlias" in {
    val firstResponse = adminIndexManager.getIndex("blabla")

    firstResponse.responseCode should be(200)
    firstResponse.indicesAndAliases.size should be(1)

    val secondResponse = adminIndexManager.getIndex("perfmon_my_test_alias")

    secondResponse.responseCode should be(200)
    secondResponse.indicesAndAliases.size should be(1)
  }
  "testIndexCanBeAccessedByAdminUsingNameOrAliasWithWildcard" in {
    val firstResponse = adminIndexManager.getIndex("bla*")

    firstResponse.responseCode should be(200)
    firstResponse.indicesAndAliases.size should be(1)

    val secondResponse = adminIndexManager.getIndex("perf*mon_my_test*")

    secondResponse.responseCode should be(200)
    secondResponse.indicesAndAliases.size should be(1)
  }
  "testIndexCanBeAccessedByUserPerfmonUsingNameOrAlias" in {
    val firstResponse = perfmonIndexManager.getIndex("blabla")

    firstResponse.responseCode should be(200)
    firstResponse.indicesAndAliases should be(Map(
      "blabla" -> Set.empty
    ))

    val secondResponse = perfmonIndexManager.getIndex("perfmon_my_test_alias")

    secondResponse.responseCode should be(200)
    secondResponse.indicesAndAliases should be(Map(
      "blabla" -> Set("perfmon_my_test_alias")
    ))
  }
  "testIndexCanBeAccessedByUserPerfmonUsingNameOrAliasWithWildcard" in {
    val firstResponse = perfmonIndexManager.getIndex("bla*")

    firstResponse.responseCode should be(200)
    firstResponse.indicesAndAliases should be(Map(
      "blabla" -> Set.empty
    ))

    val secondResponse = perfmonIndexManager.getIndex("perf*mon_my_test*")

    secondResponse.responseCode should be(200)
    secondResponse.indicesAndAliases should be(Map(
      "blabla" -> Set("perfmon_my_test_alias")
    ))
  }
  "testVietMianUserShouldBeAbleToAccessVietnamIndex" in {
    val response = vietMyanSearchManager.search("vuln-ass-all-vietnam")

    response.responseCode should be(200)
    response.searchHits.size should be(1)
  }
  "testVietMianUserShouldNotBeAbleToAccessCongoIndex" in {
    val response = vietMyanSearchManager.search("vuln-ass-all-congo")

    response.responseCode should be(404)
  }
  "testVietMianUserShouldBeAbleToSeeAllowedIndicesUsingAlias" in {
    val response = vietMyanSearchManager.search("all-subs-data")

    response.responseCode should be(200)
    response.searchHits.size should be(2)
  }
}

object SearchWithAliasesSuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    val indexManager = new IndexManager(adminRestClient, esVersion)

    documentManager.createDoc("my_data", "test", 1, ujson.read("""{"hello":"world"}""")).force()
    documentManager.createDoc("my_data", "test", 2, ujson.read("""{"hello":"there", "public":1}""")).force()

    documentManager.createDoc("blabla", 1, ujson.read("""{"hello":"there", "public":1}""")).force()

    documentManager.createDoc("vuln-ass-all-angola", "test", 1, ujson.read("""{"country":"angola"}""")).force()
    documentManager.createDoc("vuln-ass-all-china", "test", 1, ujson.read("""{"country":"china"}""")).force()
    documentManager.createDoc("vuln-ass-all-congo", "test", 1, ujson.read("""{"country":"congo"}""")).force()
    documentManager.createDoc("vuln-ass-all-myanmar", "test", 1, ujson.read("""{"country":"myanmar"}""")).force()
    documentManager.createDoc("vuln-ass-all-norge", "test", 1, ujson.read("""{"country":"norge"}""")).force()
    documentManager.createDoc("vuln-ass-all-vietnam", "test", 1, ujson.read("""{"country":"vietnam"}""")).force()

    indexManager
      .updateAliases(
        AliasAction.Add("my_data", "public_data", Some(ujson.read("""{"term":{"public":1}}"""))),
        AliasAction.Add("blabla", "perfmon_my_test_alias"),
        AliasAction.Add("vuln-ass-all-angola", "all-subs-data"),
        AliasAction.Add("vuln-ass-all-china", "all-subs-data"),
        AliasAction.Add("vuln-ass-all-congo", "all-subs-data"),
        AliasAction.Add("vuln-ass-all-myanmar", "all-subs-data"),
        AliasAction.Add("vuln-ass-all-norge", "all-subs-data"),
        AliasAction.Add("vuln-ass-all-vietnam", "all-subs-data")
      )
      .force()
  }
}