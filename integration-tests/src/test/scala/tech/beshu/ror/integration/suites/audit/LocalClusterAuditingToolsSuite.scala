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
package tech.beshu.ror.integration.suites.audit

import tech.beshu.ror.integration.suites.base.BaseAuditingToolsSuite
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.SingletonPluginTestSupport
import tech.beshu.ror.utils.containers.ElasticsearchNodeDataInitializer
import tech.beshu.ror.utils.containers.providers.ClientProvider
import tech.beshu.ror.utils.elasticsearch.BaseManager.JSON
import tech.beshu.ror.utils.elasticsearch.{ElasticsearchTweetsInitializer, IndexManager}
import tech.beshu.ror.utils.misc.Resources.getResourceContent
import tech.beshu.ror.utils.misc.Version

import scala.util.{Success, Try}

class LocalClusterAuditingToolsSuite
  extends BaseAuditingToolsSuite
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport {

  private val isDataStreamSupported = Version.greaterOrEqualThan(esVersionUsed, 7, 9, 0)

  override implicit val rorConfigFileName: String = {
    if (isDataStreamSupported) {
      "/ror_audit/enabled_auditing_tools/readonlyrest.yml"
    } else {
      "/ror_audit/enabled_auditing_tools/readonlyrest_audit_index.yml"
    }
  }

  override def nodeDataInitializer: Option[ElasticsearchNodeDataInitializer] = Some(ElasticsearchTweetsInitializer)

  override lazy val destNodeClientProvider: ClientProvider = this

  override def baseRorConfig: String = resolvedRorConfigFile.contentAsString

  override protected def baseAuditDataStreamName: Option[String] =
    Option.when(Version.greaterOrEqualThan(esVersionUsed, 7, 9, 0))("audit_data_stream")

  // Adding the ES cluster fields is disabled in the /enabled_auditing_tools/readonlyrest.yml config file (`DefaultAuditLogSerializerV1` is used)
  override def assertForEveryAuditEntry(entry: JSON): Unit = {
    entry.obj.get("es_node_name") shouldBe None
    entry.obj.get("es_cluster_name") shouldBe None
  }

  "ES" should {
    "submit audit entries" when {
      "first request uses V1 serializer, then ROR config is reloaded and second request uses V2 serializer" in {
        val initialConfig = getResourceContent(rorConfigFileName)
        rorApiManager.updateRorInIndexConfig(initialConfig).forceOKStatusOrConfigAlreadyLoaded()
        rorApiManager.reloadRorConfig().force()

        val indexManager = new IndexManager(basicAuthClient("username", "dev"), esVersionUsed)
        val response = indexManager.getIndex("twitter")
        response should have statusCode 200

        val modifiedConfig = initialConfig.replace("DefaultAuditLogSerializerV1", "DefaultAuditLogSerializerV2")
        rorApiManager.updateRorInIndexConfig(modifiedConfig).forceOKStatusOrConfigAlreadyLoaded()
        rorApiManager.reloadRorConfig().force()

        val response2 = indexManager.getIndex("twitter")
        response2 should have statusCode 200

        forEachAuditManager { adminAuditManager =>
          eventually {
            val auditEntries = adminAuditManager.getEntries.force().jsons
            auditEntries.size shouldBe 2

            auditEntries.exists(entry =>
              entry("final_state").str == "ALLOWED" &&
                entry("user").str == "username" &&
                entry("block").str.contains("name: 'Rule 1'") &&
                entry.obj.get("es_node_name").isEmpty &&
                entry.obj.get("es_cluster_name").isEmpty
            ) shouldBe true

            auditEntries.exists(entry =>
              entry("final_state").str == "ALLOWED" &&
                entry("user").str == "username" &&
                entry("block").str.contains("name: 'Rule 1'") &&
                Try(entry("es_node_name")).map(_.str) == Success("ROR_SINGLE_1") &&
                Try(entry("es_cluster_name")).map(_.str) == Success("ROR_SINGLE")
            ) shouldBe true
          }
        }
      }
    }
  }
}
