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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.{ESVersionSupportForAnyWordSpecLike, SingletonPluginTestSupport}
import tech.beshu.ror.utils.containers.ElasticsearchNodeDataInitializer
import tech.beshu.ror.utils.elasticsearch._
import tech.beshu.ror.utils.httpclient.RestClient

class ResponseFieldRuleSuite
  extends AnyWordSpec
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport
    with ESVersionSupportForAnyWordSpecLike
    with Matchers {

  override implicit val rorConfigFileName = "/response_field_rules/readonlyrest.yml"

  override def nodeDataInitializer = Some(ResponseFieldRuleSuite.nodeDataInitializer())

  "A response_field rule" should {

    "filter cluster health response in whitelist mode using json format" in {
      val dev1ClusterStateManager = new ClusterManager(basicAuthClient("dev1", "test"), esVersion = esVersionUsed)
      val healthCheck = dev1ClusterStateManager.health()

      healthCheck.responseJson.obj.isDefinedAt("cluster_name") should equal(true)
      healthCheck.responseJson.obj.isDefinedAt("status") should equal(true)
      healthCheck.responseJson.obj.isDefinedAt("active_primary_shards") should equal(false)
      healthCheck.responseJson.obj.isDefinedAt("active_shards") should equal(false)
      healthCheck.responseJson.obj.isDefinedAt("number_of_nodes") should equal(false)
    }

    "filter cluster health response in whitelist mode using yaml format" in {
      val dev1ClusterStateManager = new ClusterManagerYaml(basicAuthClient("dev1", "test"), esVersion = esVersionUsed)
      val yamlHealthCheck = dev1ClusterStateManager.health()

      yamlHealthCheck.responseYaml.isDefinedAt("cluster_name") should equal(true)
      yamlHealthCheck.responseYaml.isDefinedAt("status") should equal(true)
      yamlHealthCheck.responseYaml.isDefinedAt("active_primary_shards") should equal(false)
      yamlHealthCheck.responseYaml.isDefinedAt("active_shards") should equal(false)
      yamlHealthCheck.responseYaml.isDefinedAt("number_of_nodes") should equal(false)
    }

    "filter cat health response in blacklist mode " in {
      val dev1CatManager = new CatManager(basicAuthClient("dev1", "test"), esVersion = esVersionUsed)
      val healthCheck = dev1CatManager.healthCheck()

      healthCheck.responseJson.arr.head.obj.isDefinedAt("status") should equal(true)
      healthCheck.responseJson.arr.head.obj.isDefinedAt("cluster") should equal(false)
    }

    "filter search response in whitelist mode" in {
      val searchManager = new SearchManager(basicAuthClient("dev1", "test"))
      val result = searchManager.search(
        ujson.read("""{"query": {"terms":{"user_id": ["alice", "bob"]}}}""")
      )
      result.responseCode should be(200)
      result.searchHits.size should be(1)
      result.searchHits(0)("_source")("user_id").str should be("alice")
    }
  }

}

object ResponseFieldRuleSuite {
  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (esVersion, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient, esVersion)
    documentManager.createDoc("index1", 1, ujson.read("""{"user_id":"ivan"}""")).force()
    documentManager.createDoc("index1", 2, ujson.read("""{"user_id":"alice"}""")).force()
  }
}