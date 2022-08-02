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
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsClusterProvider, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.IndexManager.AliasAction
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, IndexManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient

import java.time.Instant

trait SearchApiSuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with ESVersionSupportForAnyWordSpecLike
    with Eventually
    with IntegrationPatience
    with Matchers {
  this: EsClusterProvider =>

  override implicit val rorConfigFileName = "/search_api/readonlyrest.yml"

  override def nodeDataInitializer = Some(SearchApiSuite.nodeDataInitializer())

  private lazy val user1SearchManager = new SearchManager(basicAuthClient("user1", "test"))

  private lazy val restrictedDevSearchManager = new SearchManager(basicAuthClient("restricted", "dev"))
  private lazy val unrestrictedDevSearchManager = new SearchManager(basicAuthClient("unrestricted", "dev"))
  private lazy val adminIndexManager = new IndexManager(adminClient, esVersionUsed)
  private lazy val perfmonIndexManager = new IndexManager(basicAuthClient("perfmon", "dev"), esVersionUsed)
  private lazy val vietMyanSearchManager = new SearchManager(basicAuthClient("VIET_MYAN", "dev"))

  "_search" should {
    "be allowed" when {
      "data stream is being searched by full name" excludeES(allEs7xBelowEs77x) in {
        val result = user1SearchManager.search("logs-0001")

        result.responseCode should be (200)
        result.searchHits.map(_("_index").str).sorted.distinct should be (List("logs-0001"))
        result.searchHits.map(_("_id").str).sorted should be (List("1", "2"))
      }
      "data stream is being searched by name with wildcard" excludeES(allEs7xBelowEs77x) in {
        val result = user1SearchManager.search("logs-*2")

        result.responseCode should be (200)
        result.searchHits.map(_("_index").str).sorted.distinct should be (List("logs-0002"))
        result.searchHits.map(_("_id").str).sorted should be (List("1"))
      }
      "data stream is being searched by full alias name" excludeES(allEs7xBelowEs77x) in {
        val result = user1SearchManager.search("all-logs")

        result.responseCode should be (200)
        result.searchHits.map(_("_index").str).sorted.distinct should be (List("logs-0001", "logs-0002"))
        result.searchHits.map(_("_id").str).sorted should be (List("1", "1", "2"))
      }
      "data stream is being searched by alias name with wildcard" excludeES(allEs7xBelowEs77x) in {
        val result = user1SearchManager.search("all-*")

        result.responseCode should be (200)
        result.searchHits.map(_("_index").str).sorted.distinct should be (List("logs-0001", "logs-0002"))
        result.searchHits.map(_("_id").str).sorted should be (List("1", "1", "2"))
      }
    }
  }

  "Real life tests" should {
    "pass" when {
      "it's a direct index query" in eventually {
        val response = unrestrictedDevSearchManager.search("my_data")

        response.responseCode should be(200)
        response.searchHits.size should be(2)
      }
      "it's an alias query" in {
        val response = unrestrictedDevSearchManager.search("public_data")

        response.responseCode should be(200)
        response.searchHits.size should be(1)
      }
      "it's an alias as wildcard" in {
        val response = unrestrictedDevSearchManager.search("pub*")

        response.responseCode should be(200)
        response.searchHits.size should be(1)
      }
      // Tests with indices rule restricting to "pub*"
      "it's a restricted pure index" in {
        val response = restrictedDevSearchManager.search("my_data")

        response.responseCode should be(404)
      }
      "it's a restricted alias" in {
        val response = restrictedDevSearchManager.search("public_data")

        response.responseCode should be(200)
        response.searchHits.size should be(1)
      }
      "it's a restricted alias as wildcard" in {
        val response = restrictedDevSearchManager.search("public*")

        response.responseCode should be(200)
        response.searchHits.size should be(1)
      }
      "it's a restricted alias as half wildcard" in {
        val response = restrictedDevSearchManager.search("pu*")

        response.responseCode should be(200)
        response.searchHits.size should be(1)
      }
      // real cases from github
      "it's an index that can be accessed by admin using name or alias" in {
        val firstResponse = adminIndexManager.getIndex("blabla")

        firstResponse.responseCode should be(200)
        firstResponse.indicesAndAliases.size should be(1)

        val secondResponse = adminIndexManager.getIndex("perfmon_my_test_alias")

        secondResponse.responseCode should be(200)
        secondResponse.indicesAndAliases.size should be(1)
      }
      "it's an index that can be accessed by admin using name or alias with wildcard" in {
        val firstResponse = adminIndexManager.getIndex("bla*")

        firstResponse.responseCode should be(200)
        firstResponse.indicesAndAliases.size should be(1)

        val secondResponse = adminIndexManager.getIndex("perf*mon_my_test*")

        secondResponse.responseCode should be(200)
        secondResponse.indicesAndAliases.size should be(1)
      }
      "it's an index that can be accessed by user 'perfmon' using name or alias" in {
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
      "it's an index that can be accessed by user 'perfmon' using name or alias with wildcard" in {
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
      "it's a 'VIET_MYAN' user who should be able to access 'Vietnam' index" in {
        val response = vietMyanSearchManager.search("vuln-ass-all-vietnam")

        response.responseCode should be(200)
        response.searchHits.size should be(1)
      }
      "it's a 'VIET_MYAN' user who should not be able to access 'Congo' index" in {
        val response = vietMyanSearchManager.search("vuln-ass-all-congo")

        response.responseCode should be(404)
      }
      "it's a 'VIET_MYAN' user who should be able to see allowed indices using alias" in {
        val response = vietMyanSearchManager.search("all-subs-data")

        response.responseCode should be(200)
        response.searchHits.size should be(2)
      }
    }
  }
}

object SearchApiSuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    val indexManager = new IndexManager(adminRestClient, esVersion)

    createDataStreamWithAlias(indexManager, documentManager)
    createRealLifeTestsDocumentsAndAliases(indexManager, documentManager)
  }

  private def createDataStreamWithAlias(indexManager: IndexManager,
                                        documentManager: DocumentManager) = {
    documentManager.createDoc("logs-0001", 1, ujson.read(s"""{ "message":"test1", "@timestamp": "@${Instant.now().toEpochMilli}"}"""))
    documentManager.createDoc("logs-0001", 2, ujson.read(s"""{ "message":"test2", "@timestamp": "@${Instant.now().toEpochMilli}"}"""))
    documentManager.createDoc("logs-0002", 1, ujson.read(s"""{ "message":"test3", "@timestamp": "@${Instant.now().toEpochMilli}"}"""))

    indexManager.createAliasOf("logs-0001", "all-logs")
    indexManager.createAliasOf("logs-0002", "all-logs")
  }

  private def createRealLifeTestsDocumentsAndAliases(indexManager: IndexManager,
                                                     documentManager: DocumentManager): Unit = {
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