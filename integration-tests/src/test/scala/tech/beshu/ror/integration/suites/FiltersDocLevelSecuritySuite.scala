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

import monix.execution.atomic.AtomicInt
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyWordSpecLike, SingletonPluginTestSupport}
import tech.beshu.ror.utils.JsonReader.ujsonRead
import tech.beshu.ror.utils.containers.ElasticsearchNodeDataInitializer
import tech.beshu.ror.utils.elasticsearch.{DocumentManager, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.CustomScalaTestMatchers

//TODO change test names. Current names are copies from old java integration tests
class FiltersDocLevelSecuritySuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport
    with ESVersionSupportForAnyWordSpecLike
    with CustomScalaTestMatchers {

  override implicit val rorConfigFileName: String = "/filters/readonlyrest.yml"

  override def nodeDataInitializer = Some(FiltersDocLevelSecuritySuite.nodeDataInitializer())

  "testDirectSingleIdxa" in {
    val searchManager = new SearchManager(
      adminClient, esVersionUsed,
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
  }

  "testHeaderReplacement" in {
    val searchManager = new SearchManager(
      adminClient, esVersionUsed,
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
  }

  "testStar" in {
    val searchManager = new SearchManager(
      adminClient, esVersionUsed,
      Map("x-api-key" -> "star")
    )
    val response = searchManager.search("testfiltera")

    response should have statusCode 200
    response.body.contains("a1") shouldBe true
    response.body.contains("a2") shouldBe false
    response.body.contains("b1") shouldBe false
    response.body.contains("b2") shouldBe false
    response.body.contains("c1") shouldBe false
    response.body.contains("c2") shouldBe false
  }

  "testDirectMultipleIdxbandc" in {
    val searchManager = new SearchManager(
      adminClient, esVersionUsed,
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
  }

  "testDirectSingleIdxd" in {
    val searchManager = new SearchManager(
      adminClient, esVersionUsed,
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

    val sourceJson = response.searchHits.head.obj("_source")
    sourceJson.obj.size should be(2)
    sourceJson("title").str should be("d1")
    sourceJson("dummy").bool should be(true)
  }

  "tesANoCache" in {
    val searchManager = new SearchManager(
      adminClient, esVersionUsed,
      Map("x-api-key" -> "a_nofilter")
    )
    val firstResponse = searchManager.search("testfiltera")
    firstResponse should have statusCode 200

    val searchManager2 = new SearchManager(
      adminClient, esVersionUsed,
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
  }
}

object FiltersDocLevelSecuritySuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    val docId = AtomicInt(0)
    insertDoc("a1", "a", "title")
    insertDoc("a2", "a", "title")
    insertDoc("b1", "bandc", "title")
    insertDoc("b2", "bandc", "title")
    insertDoc("c1", "bandc", "title")
    insertDoc("c2", "bandc", "title")
    insertDoc("d1", "d", "title")
    insertDoc("d2", "d", "title")
    insertDoc("d1", "d", "nottitle")
    insertDoc("d2", "d", "nottitle")

    def insertDoc(docName: String, idx: String, field: String): Unit = {
      documentManager
        .createDoc(
          s"testfilter$idx",
          docId.incrementAndGet(),
          ujsonRead(s"""{"$field": "$docName", "dummy": true}""")
        )
        .force()
    }
  }
}