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

import com.dimafeng.testcontainers.ForAllTestContainer
import org.apache.commons.lang.StringEscapeUtils.escapeJava
import org.scalatest.WordSpec
import org.scalatest.Matchers._
import tech.beshu.ror.utils.containers.{ElasticsearchNodeDataInitializer, ReadonlyRestEsCluster, ReadonlyRestEsClusterContainer}
import tech.beshu.ror.utils.elasticsearch.{ActionManager, DocumentManager}
import tech.beshu.ror.utils.misc.CirceJsonHelper.jsonFrom
import tech.beshu.ror.utils.misc.Resources.getResourceContent

class AdminApiTests extends WordSpec with ForAllTestContainer {

  override val container: ReadonlyRestEsClusterContainer = ReadonlyRestEsCluster.createLocalClusterContainer(
    name = "ROR1",
    rorConfigFileName = "/admin_api/readonlyrest.yml",
    numberOfInstances = 1,
    AdminApiTests.nodeDataInitializer()
  )

  private lazy val adminActionManager = new ActionManager(container.nodesContainers.head.adminClient)

  "An admin REST API" should {
    "allow admin to force reload current settings" in {
      val result  = adminActionManager.actionPost("_readonlyrest/admin/refreshconfig")
      result.getResponseCode should be (200)
      if(result.getResponseJson.get("status") == "ok") {
        result.getResponseJson.get("message") should be ("ReadonlyREST config was reloaded with success!")
      } else {
        result.getResponseJson.get("message") should be ("Current configuration is up to date")
      }
    }
    "provide update index configuration method" which {
      "updates index config when passed config is correct" in {
        val result  = adminActionManager.actionPost(
          "_readonlyrest/admin/config",
          s"""{"settings": "${escapeJava(getResourceContent("/admin_api/readonlyrest_to_update.yml"))}"}"""
        )
        result.getResponseCode should be (200)
        result.getResponseJson.get("status") should be ("ok")
        result.getResponseJson.get("message") should be ("updated settings")
      }
      "not allow to update index configuration" when {
        "passed config is malformed" in {
          val result  = adminActionManager.actionPost(
            "_readonlyrest/admin/config",
            s"${escapeJava(getResourceContent("/admin_api/readonlyrest_to_update.yml"))}"
          )
          result.getResponseCode should be (200)
          result.getResponseJson.get("status") should be ("ko")
          result.getResponseJson.get("message") should be ("JSON body malformed")
        }
      }
    }
    "get content of file config" in {
      val result = adminActionManager.actionGet("_readonlyrest/admin/config/file")
      result.getResponseCode should be (200)
      result.getResponseJson.get("status") should be ("ok")
      jsonFrom(result.getResponseJson.get("message").asInstanceOf[String]) should be {
        jsonFrom(getResourceContent("/admin_api/readonlyrest.yml"))
      }
    }
    "get content of index config" in {
      val result  = adminActionManager.actionPost(
        "_readonlyrest/admin/config",
        s"""{"settings": "${escapeJava(getResourceContent("/admin_api/readonlyrest_index.yml"))}"}"""
      )
      result.getResponseCode should be (200)
      result.getResponseJson.get("status") should be ("ok")

      val getIndexConfigResult = adminActionManager.actionGet("_readonlyrest/admin/config")
      getIndexConfigResult.getResponseCode should be (200)
      getIndexConfigResult.getResponseJson.get("status") should be ("ok")
      jsonFrom(getIndexConfigResult.getResponseJson.get("message").asInstanceOf[String]) should be {
        jsonFrom(getResourceContent("/admin_api/readonlyrest_index.yml"))
      }
    }
  }

}


object AdminApiTests {

  private def nodeDataInitializer(): ElasticsearchNodeDataInitializer = (documentManager: DocumentManager) => {
    documentManager.insertDoc("/test1_index/test/1", "{\"hello\":\"world\"}")
    documentManager.insertDoc("/test2_index/test/1", "{\"hello\":\"world\"}")
    documentManager.insertDoc(
      "/.readonlyrest/settings/1",
      s"""{"settings": "${escapeJava(getResourceContent("/admin_api/readonlyrest_index.yml"))}"}"""
    )
  }
}