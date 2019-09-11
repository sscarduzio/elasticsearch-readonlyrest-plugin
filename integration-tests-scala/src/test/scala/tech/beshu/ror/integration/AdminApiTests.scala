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
package tech.beshu.ror.integration

import com.dimafeng.testcontainers.{ForAllTestContainer, MultipleContainers}
import org.apache.commons.lang.StringEscapeUtils.escapeJava
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, ReadonlyRestEsCluster}
import tech.beshu.ror.utils.elasticsearch.{ActionManager, DocumentManager}
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.Resources.getResourceContent

class AdminApiTests extends WordSpec with ForAllTestContainer {

  private val rorWithIndexConfig = ReadonlyRestEsCluster.createLocalClusterContainer(
    name = "ROR1",
    rorConfigFileName = "/admin_api/readonlyrest.yml",
    numberOfInstances = 1,
    AdminApiTests.nodeDataInitializer()
  )
  private val rorWithNoIndexConfig = ReadonlyRestEsCluster.createLocalClusterContainer(
    name = "ROR2",
    rorConfigFileName = "/admin_api/readonlyrest.yml",
    numberOfInstances = 1
  )
  override val container: MultipleContainers = MultipleContainers(rorWithIndexConfig, rorWithNoIndexConfig)

  private lazy val rorWithIndexConfigAdminActionManager = new ActionManager(rorWithIndexConfig.nodesContainers.head.adminClient)
  private lazy val rorWithNoIndexConfigAdminActionManager = new ActionManager(rorWithNoIndexConfig.nodesContainers.head.adminClient)

  "An admin REST API" should {
    "allow admin to force reload current settings" in {
      val result = rorWithIndexConfigAdminActionManager.actionPost("_readonlyrest/admin/refreshconfig")
      result.getResponseCode should be(200)
      if (result.getResponseJson.get("status") == "ok") {
        result.getResponseJson.get("message") should be("ReadonlyREST settings were reloaded with success!")
      } else {
        result.getResponseJson.get("message") should be("Current settings are up to date")
      }
    }
    "provide update index configuration method" which {
      "updates index config when passed config is correct" in {
        val result = rorWithIndexConfigAdminActionManager.actionPost(
          "_readonlyrest/admin/config",
          s"""{"settings": "${escapeJava(getResourceContent("/admin_api/readonlyrest_to_update.yml"))}"}"""
        )
        result.getResponseCode should be(200)
        result.getResponseJson.get("status") should be("ok")
        result.getResponseJson.get("message") should be("updated settings")
      }
      "not allow to update index configuration" when {
        "passed config is malformed" in {
          val result = rorWithIndexConfigAdminActionManager.actionPost(
            "_readonlyrest/admin/config",
            s"${escapeJava(getResourceContent("/admin_api/readonlyrest_to_update.yml"))}"
          )
          result.getResponseCode should be(200)
          result.getResponseJson.get("status") should be("ko")
          result.getResponseJson.get("message") should be("JSON body malformed")
        }
      }
    }
    "get content of file config" in {
      val result = rorWithIndexConfigAdminActionManager.actionGet("_readonlyrest/admin/config/file")
      result.getResponseCode should be(200)
      result.getResponseJson.get("status") should be("ok")
      result.getResponseJson.get("message").asInstanceOf[String] should be {
        getResourceContent("/admin_api/readonlyrest.yml")
      }
    }
    "get content of index config" in {
      val result = rorWithIndexConfigAdminActionManager.actionPost(
        "_readonlyrest/admin/config",
        s"""{"settings": "${escapeJava(getResourceContent("/admin_api/readonlyrest_index.yml"))}"}"""
      )
      result.getResponseCode should be(200)
      result.getResponseJson.get("status") should be("ok")

      val getIndexConfigResult = rorWithIndexConfigAdminActionManager.actionGet("_readonlyrest/admin/config")
      getIndexConfigResult.getResponseCode should be(200)
      getIndexConfigResult.getResponseJson.get("status") should be("ok")
      getIndexConfigResult.getResponseJson.get("message").asInstanceOf[String] should be {
        getResourceContent("/admin_api/readonlyrest_index.yml")
      }
    }
    "return 'empty' status" when {
      "there is no in-index config" in {
        val getIndexConfigResult = rorWithNoIndexConfigAdminActionManager.actionGet("_readonlyrest/admin/config")
        getIndexConfigResult.getResponseCode should be(200)
        getIndexConfigResult.getResponseJson.get("status") should be("empty")
        getIndexConfigResult.getResponseJson.get("message").asInstanceOf[String] should be {
          "Cannot find settings index"
        }
      }
    }
    "not allow to reload current settings" when {
      "config is malformed" when {
        "LDAP is not achievable" in {
          val result = rorWithIndexConfigAdminActionManager.actionPost(
            "_readonlyrest/admin/config",
            s"""{"settings": "${escapeJava(getResourceContent("/admin_api/readonlyrest_with_ldap.yml"))}"}"""
          )
          result.getResponseCode should be(200)
          result.getResponseJson.get("status") should be("ko")
          result.getResponseJson.get("message") should be("Cannot reload new settings: Errors:\nThere was a problem with LDAP connection to: ldap://localhost:389")
        }
      }
    }
  }
}

object AdminApiTests {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (_, adminRestClient: RestClient) => {
    val documentManager = new DocumentManager(adminRestClient)
    documentManager.insertDoc("/test1_index/test/1", "{\"hello\":\"world\"}")
    documentManager.insertDoc("/test2_index/test/1", "{\"hello\":\"world\"}")
    documentManager.insertDoc(
      "/.readonlyrest/settings/1",
      s"""{"settings": "${escapeJava(getResourceContent("/admin_api/readonlyrest_index.yml"))}"}"""
    )
  }
}