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
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, EsContainerCreator}
import tech.beshu.ror.utils.elasticsearch.{DocumentManagerJ, IndexManagerJ, SearchManager}
import tech.beshu.ror.utils.httpclient.RestClient

//TODO change test names. Current names are copies from old java integration tests
trait HostsRuleSuite
  extends WordSpec
    with BaseSingleNodeEsClusterTest
    with Matchers {
  this: EsContainerCreator =>

  override implicit val rorConfigFileName = "/hosts_rule/readonlyrest.yml"

  override def nodeDataInitializer = Some(HostsRuleSuite.nodeDataInitializer())

  private lazy val searchManager = new SearchManager(basicAuthClient("blabla", "kibana"))

  "testGet" in {
    val response = searchManager.search()

    response.responseCode shouldBe 401
  }
}

object HostsRuleSuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    val documentManager = new DocumentManagerJ(adminRestClient)
    val indexManager = new IndexManagerJ(adminRestClient)

    indexManager.create("empty_index")
    documentManager.insertDocAndWaitForRefresh(
      "/.kibana/documents/doc1",
      """{"id": "asd123"}"""
    )
  }
}