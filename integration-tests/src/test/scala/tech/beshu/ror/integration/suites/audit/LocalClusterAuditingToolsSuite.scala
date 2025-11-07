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
import tech.beshu.ror.utils.elasticsearch.{AuditIndexManager, ElasticsearchTweetsInitializer, IndexManager}
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
        val indexManager = new IndexManager(basicAuthClient("username", "dev"), esVersionUsed)

        updateRorConfigToUseSerializer("tech.beshu.ror.audit.instances.DefaultAuditLogSerializerV1")
        performAndAssertExampleSearchRequest(indexManager)

        updateRorConfigToUseSerializer("tech.beshu.ror.audit.instances.DefaultAuditLogSerializerV2")
        performAndAssertExampleSearchRequest(indexManager)

        forEachAuditManager { adminAuditManager =>
          eventually {
            val auditEntries = adminAuditManager.getEntries.force().jsons

            // On Linux we could assert number of entries equal to 2.
            // On Windows reloading config sometimes takes a little longer,
            // and there are 3 or more messages (from before reload, so not important)
            auditEntries.size should be >= 2
            auditEntries.exists(entry =>
              entry("final_state").str == "ALLOWED" &&
                entry("user").str == "username" &&
                entry("logged_user").str == "username" &&
                entry("presented_identity").str == "username" &&
                entry("block").str.contains("name: 'Rule 1'") &&
                entry.obj.get("es_node_name").isEmpty &&
                entry.obj.get("es_cluster_name").isEmpty
            ) shouldBe true
            auditEntries.exists(entry =>
              entry("final_state").str == "ALLOWED" &&
                entry("user").str == "username" &&
                entry("logged_user").str == "username" &&
                entry("presented_identity").str == "username" &&
                entry("block").str.contains("name: 'Rule 1'") &&
                Try(entry("es_node_name")).map(_.str) == Success("ROR_SINGLE_1") &&
                Try(entry("es_cluster_name")).map(_.str) == Success("ROR_SINGLE")
            ) shouldBe true
          }
        }
        updateRorConfigToUseSerializer("tech.beshu.ror.audit.instances.DefaultAuditLogSerializerV1")
      }
      "using ReportingAllEventsAuditLogSerializer" in {
        val indexManager = new IndexManager(basicAuthClient("username", "dev"), esVersionUsed)

        updateRorConfigToUseSerializer("tech.beshu.ror.audit.instances.FullAuditLogSerializer")
        performAndAssertExampleSearchRequest(indexManager)

        forEachAuditManager { adminAuditManager =>
          eventually {
            val auditEntries = adminAuditManager.getEntries.force().jsons
            assert(auditEntries.size >= 3)

            auditEntries.exists(entry =>
              entry("final_state").str == "ALLOWED" &&
                entry("user").str == "username" &&
                entry("logged_user").str == "username" &&
                entry("presented_identity").str == "username" &&
                entry("block").str.contains("name: 'Rule 1'") &&
                Try(entry("es_node_name")).map(_.str) == Success("ROR_SINGLE_1") &&
                Try(entry("es_cluster_name")).map(_.str) == Success("ROR_SINGLE") &&
                entry.obj.get("content").isEmpty
            ) shouldBe true

            auditEntries.exists(entry => entry("path").str == "/_readonlyrest/admin/refreshconfig/") shouldBe true
            auditEntries.exists(entry => entry("path").str == "/audit_index/_search/") shouldBe true
          }
        }
        updateRorConfigToUseSerializer("tech.beshu.ror.audit.instances.DefaultAuditLogSerializerV1")
        // This test uses serializer, that reports all events. We need to wait a moment, to ensure that there will be no more events using that serializer
        Thread.sleep(3000)
      }
      "using ReportingAllEventsWithQueryAuditLogSerializer" in {
        val indexManager = new IndexManager(basicAuthClient("username", "dev"), esVersionUsed)

        updateRorConfigToUseSerializer("tech.beshu.ror.audit.instances.FullAuditLogWithQuerySerializer")
        performAndAssertExampleSearchRequest(indexManager)

        forEachAuditManager { adminAuditManager =>
          eventually {
            val auditEntries = adminAuditManager.getEntries.force().jsons
            assert(auditEntries.size >= 3)
            auditEntries.exists(entry =>
              entry("final_state").str == "ALLOWED" &&
                entry("user").str == "username" &&
                entry("logged_user").str == "username" &&
                entry("presented_identity").str == "username" &&
                entry("block").str.contains("name: 'Rule 1'") &&
                Try(entry("es_node_name")).map(_.str) == Success("ROR_SINGLE_1") &&
                Try(entry("es_cluster_name")).map(_.str) == Success("ROR_SINGLE") &&
                Try(entry("content")).map(_.str) == Success("")
            ) shouldBe true

            auditEntries.exists(entry => entry("path").str == "/_readonlyrest/admin/refreshconfig/") shouldBe true
            auditEntries.exists(entry => entry("path").str == "/audit_index/_search/") shouldBe true
          }
        }
        updateRorConfigToUseSerializer("tech.beshu.ror.audit.instances.DefaultAuditLogSerializerV1")
        // This test uses serializer, that reports all events. We need to wait a moment, to ensure that there will be no more events using that serializer
        Thread.sleep(3000)
      }
      "using ConfigurableQueryAuditLogSerializer" in {
        val indexManager = new IndexManager(basicAuthClient("username", "dev"), esVersionUsed)

        updateRorConfig(
          originalString = """type: "static"""",
          newString = """type: "configurable"""",
        )
        performAndAssertExampleSearchRequest(indexManager)

        forEachAuditManager { adminAuditManager =>
          eventually {
            val auditEntries = adminAuditManager.getEntries.force().jsons
            auditEntries.size shouldBe 1

            auditEntries.exists(entry =>
              entry("node_name_with_static_suffix").str == "ROR_SINGLE_1 with suffix" &&
                entry("another_field").str == "ROR_SINGLE GET" &&
                entry("tid").numOpt.isDefined &&
                entry("bytes").num == 0
            ) shouldBe true
          }
        }
        updateRorConfigToUseSerializer("tech.beshu.ror.audit.instances.DefaultAuditLogSerializerV1")
      }
      "using ECS serializer" in {
        val indexManager = new IndexManager(basicAuthClient("username", "dev"), esVersionUsed)
        // We need to create a new index with a different name for this test, because the ECS schema
        // is not compatible with the Json object created by other serializers in previous tests.
        val ecsAuditIndexName = "ecs_audit_index"
        updateRorConfig(
          replacements = Map(
            """type: "static"""" -> """type: "ecs"""",
            "audit_index" -> ecsAuditIndexName,
          )
        )
        val auditIndexManager = new AuditIndexManager(destNodeClientProvider.adminClient, esVersionUsed, ecsAuditIndexName)
        performAndAssertExampleSearchRequest(indexManager)
        eventually {
          val auditEntries = auditIndexManager.getEntries.force().jsons
          auditEntries.exists { entry =>
            // ecs
            entry("ecs")("version").str == "1.6.0" &&
              // trace
              entry("trace")("id").strOpt.isDefined &&
              // timestamp (exists, not verified for exact value)
              entry("@timestamp").strOpt.isDefined &&
              // destination
              entry("destination")("address").strOpt.isDefined &&
              // source
              entry("source")("address").strOpt.isDefined &&
              // http request
              entry("http")("request")("method").str == "GET" &&
              entry("http")("request")("body")("bytes").num == 0 &&
              entry("http")("request")("body")("content").str == "" &&
              // event
              entry("event")("id").strOpt.isDefined &&
              entry("event")("duration").numOpt.isDefined &&
              entry("event")("action").str == "indices:admin/get" &&
              entry("event")("reason").str == "GetIndexRequest" &&
              entry("event")("outcome").str == "success" &&
              // error (empty object)
              entry("error").obj.isEmpty &&
              // user
              entry("user")("name").str == "username" &&
              entry("user")("effective").obj.isEmpty &&
              // url
              entry("url")("path").str == "/twitter/" &&
              // labels
              entry("labels")("es_cluster_name").str == "ROR_SINGLE" &&
              entry("labels")("es_node_name").str == "ROR_SINGLE_1" &&
              entry("labels")("es_task_id").numOpt.isDefined &&
              entry("labels")("ror_involved_indices").arrOpt.isDefined &&
              entry("labels")("ror_acl_history").str == "[CONTAINER ADMIN-> RULES:[auth_key->false] RESOLVED:[indices=twitter]], [Rule 1-> RULES:[auth_key->true, methods->true, indices->true] RESOLVED:[user=username;indices=twitter]]" &&
              entry("labels")("ror_final_state").str == "ALLOWED" &&
              entry("labels")("ror_detailed_reason").str == "{ name: 'Rule 1', policy: ALLOW, rules: [auth_key, methods, indices]"
          } shouldBe true
        }
        updateRorConfigToUseSerializer("tech.beshu.ror.audit.instances.DefaultAuditLogSerializerV1")
      }
    }
  }

  private def performAndAssertExampleSearchRequest(indexManager: IndexManager) = {
    val response = indexManager.getIndex("twitter")
    response should have statusCode 200
  }

  private def updateRorConfigToUseSerializer(serializer: String) = updateRorConfig(
    originalString = """class_name: "tech.beshu.ror.audit.instances.DefaultAuditLogSerializerV1"""",
    newString = s"""class_name: "$serializer""""
  )

  private def updateRorConfig(originalString: String, newString: String): Unit =
    updateRorConfig(Map(originalString -> newString))

  private def updateRorConfig(replacements: Map[String, String]): Unit = {
    val initialConfig = getResourceContent(rorConfigFileName)
    val modifiedConfig = replacements.foldLeft(initialConfig) { case (soFar, (originalString, newString)) =>
      soFar.replace(originalString, newString)
    }
    rorApiManager.updateRorInIndexConfig(modifiedConfig).forceOKStatusOrConfigAlreadyLoaded()
    rorApiManager.reloadRorConfig().force()
  }
}
