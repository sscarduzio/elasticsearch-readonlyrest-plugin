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

import org.scalatest.{Matchers, WordSpec}
import tech.beshu.ror.integration.suites.base.support.{BaseIntegrationTest, SingleClientSupport}
import tech.beshu.ror.utils.containers.generic.{ElasticsearchNodeDataInitializer, EsClusterSettings, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{DocumentManagerJ, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient

trait FiltersAndFieldsSecuritySuite
  extends WordSpec
    with BaseIntegrationTest
    with SingleClientSupport
    with Matchers {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/fls_dls/readonlyrest.yml"

  override lazy val targetEs = container.nodesContainers.head

  override lazy val container = createLocalClusterContainer(
    EsClusterSettings(
      name = "ROR1",
      nodeDataInitializer = FiltersAndFieldsSecuritySuite.nodeDataInitializer()
    )
  )

  "testDirectSingleIdxa" in {
    val searchManager = new SearchManager(
      adminClient,
      Map("x-api-key" -> "g")
    )
    val response = searchManager.search("/testfiltera/_search")

    response.responseCode should be(200)
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
    val response = searchManager.search("/testfiltera/_search")

    response.responseCode should be(200)
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
    val response = searchManager.search("/testfilterbandc/_search")

    response.responseCode should be(200)
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
    val response = searchManager.search("/testfilterd/_search")

    response.responseCode should be(200)
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
    val firstResponse = searchManager.search("/testfiltera/_search")
    firstResponse.responseCode shouldBe 200

    val searchManager2 = new SearchManager(
      adminClient,
      Map("x-api-key" -> "g")
    )

    val response = searchManager2.search("/testfiltera/_search")

    response.responseCode should be(200)
    response.body.contains("a1") shouldBe true
    response.body.contains("a2") shouldBe false
    response.body.contains("b1") shouldBe false
    response.body.contains("b2") shouldBe false
    response.body.contains("c1") shouldBe false
    response.body.contains("c2") shouldBe false
    response.body.contains("dummy") shouldBe false
  }
}

object FiltersAndFieldsSecuritySuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    val documentManager = new DocumentManagerJ(adminRestClient)
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
      val path = s"/testfilter$idx/documents/doc-$docName${Math.random().toString}"
      val entity = s"""{"$field": "$docName", "dummy": true}"""

      documentManager.insertDocAndWaitForRefresh(path, entity)
    }
  }
}
