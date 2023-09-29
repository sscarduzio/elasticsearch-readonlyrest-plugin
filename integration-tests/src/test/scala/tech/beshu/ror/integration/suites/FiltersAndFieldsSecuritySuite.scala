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
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyWordSpecLike, SingletonPluginTestSupport}
import tech.beshu.ror.utils.containers.ElasticsearchNodeDataInitializer
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers

//TODO change test names. Current names are copies from old java integration tests
class FiltersAndFieldsSecuritySuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport
    with ESVersionSupportForAnyWordSpecLike
    with CustomScalaTestMatchers {

  override implicit val rorConfigFileName: String = "/fls_dls/readonlyrest.yml"

  override def nodeDataInitializer = Some(FiltersAndFieldsSecuritySuite.nodeDataInitializer())

  "testDirectSingleIdxa" in {
    val searchManager = new SearchManager(
      adminClient,
      Map("x-api-key" -> "g")
    )
    val response = searchManager.search("testfiltera")

    response should have statusCode 200
    response.body.contains("a1") shouldBe true
    response.body.contains("a2") shouldBe false
    response.body.contains("b1") shouldBe false
    response.body.contains("b2") shouldBe false
    response.body.contains("c1") shouldBe false
    response.body.contains("c2") shouldBe false
    response.body.contains("dummy") shouldBe false
  }

  "testHeaderReplacement" in {
    val searchManager = new SearchManager(
      adminClient,
      Map("x-api-key" -> "put-the-header", "x-randomheader" -> "value")
    )
    val response = searchManager.search("testfiltera")

    response should have statusCode 200
    response.body.contains("a1") shouldBe true
    response.body.contains("a2") shouldBe false
    response.body.contains("b1") shouldBe false
    response.body.contains("b2") shouldBe false
    response.body.contains("c1") shouldBe false
    response.body.contains("c2") shouldBe false
    response.body.contains("dummy") shouldBe false
  }

  "testDirectMultipleIdxbandc" in {
    val searchManager = new SearchManager(
      adminClient,
      Map("x-api-key" -> "g")
    )
    val response = searchManager.search("testfilterbandc")

    response should have statusCode 200
    response.body.contains("a1") shouldBe false
    response.body.contains("a2") shouldBe false
    response.body.contains("b1") shouldBe true
    response.body.contains("b2") shouldBe false
    response.body.contains("c1") shouldBe false
    response.body.contains("c2") shouldBe true
    response.body.contains("dummy") shouldBe false
  }

  "testDirectSingleIdxd" in {
    val searchManager = new SearchManager(
      adminClient,
      Map("x-api-key" -> "g")
    )
    val response = searchManager.search("testfilterd")

    response should have statusCode 200
    response.body.contains("a1") shouldBe false
    response.body.contains("a2") shouldBe false
    response.body.contains("b1") shouldBe false
    response.body.contains("b2") shouldBe false
    response.body.contains("c1") shouldBe false
    response.body.contains("c2") shouldBe false
    response.body.contains("dummy") shouldBe false

    response.body.contains(""""title":"d1"""") shouldBe true
    response.body.contains(""""title":"d2"""") shouldBe false
    response.body.contains(""""nottitle":"d1"""") shouldBe false
    response.body.contains(""""nottitle":"d2"""") shouldBe false
  }

  "tesANoCache" in {
    val searchManager = new SearchManager(
      adminClient,
      Map("x-api-key" -> "a_nofilter")
    )
    val firstResponse = searchManager.search("testfiltera")
    firstResponse should have statusCode 200

    val searchManager2 = new SearchManager(
      adminClient,
      Map("x-api-key" -> "g")
    )

    val response = searchManager2.search("testfiltera")

    response should have statusCode 200
    response.body.contains("a1") shouldBe true
    response.body.contains("a2") shouldBe false
    response.body.contains("b1") shouldBe false
    response.body.contains("b2") shouldBe false
    response.body.contains("c1") shouldBe false
    response.body.contains("c2") shouldBe false
    response.body.contains("dummy") shouldBe false
  }

  "mSearch with filter" in {
    val searchManager = new SearchManager(basicAuthClient("dev", "test"))
    val response = searchManager.mSearch(
      """{"index":"index-01"}""",
      """{"query" : {"match_all" : {}}}""",
      """{"index":"index-02"}""",
      """{"query" : {"match_all" : {}}}"""
    )

    response should have statusCode 200
  }
}

object FiltersAndFieldsSecuritySuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    insertTestFilterDoc("a1", "a", "title")
    insertTestFilterDoc("a2", "a", "title")
    insertTestFilterDoc("b1", "bandc", "title")
    insertTestFilterDoc("b2", "bandc", "title")
    insertTestFilterDoc("c1", "bandc", "title")
    insertTestFilterDoc("c2", "bandc", "title")
    insertTestFilterDoc("d1", "d", "title")
    insertTestFilterDoc("d2", "d", "title")
    insertTestFilterDoc("d1", "d", "nottitle")
    insertTestFilterDoc("d2", "d", "nottitle")
    insertDoc("1", "index-01", "plugins_name")

    def insertTestFilterDoc(docName: String, idx: String, field: String): Unit = {
      insertDoc(docName, s"testfilter$idx", field)
    }
    def insertDoc(docName: String, idx: String, field: String): Unit = {
      val entity = ujson.read(s"""{"$field": "$docName", "dummy": true}""")

      documentManager
        .createDoc(idx, "documents", s"doc-$docName${Math.random().toString}", entity)
        .force()
    }
  }
}
