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

import cats.data.NonEmptyList
import tech.beshu.ror.integration.suites.base.BaseAuditingToolsSuite
import tech.beshu.ror.integration.suites.base.support.BaseSingleNodeEsClusterTest
import tech.beshu.ror.integration.utils.SingletonPluginTestSupport
import tech.beshu.ror.utils.containers.ElasticsearchNodeDataInitializer
import tech.beshu.ror.utils.containers.providers.ClientProvider
import tech.beshu.ror.utils.elasticsearch.BaseManager.JSON
import tech.beshu.ror.utils.elasticsearch.{AuditIndexManager, ElasticsearchTweetsInitializer, IndexManager}
import tech.beshu.ror.utils.misc.Resources.getResourceContent
import tech.beshu.ror.utils.misc.{CustomScalaTestMatchers, Version}

class LocalClusterAuditingToolsSuite
  extends BaseAuditingToolsSuite
    with BaseSingleNodeEsClusterTest
    with SingletonPluginTestSupport
    with CustomScalaTestMatchers {

  private val isDataStreamSupported = Version.greaterOrEqualThan(esVersionUsed, 7, 9, 0)

  override implicit val rorSettingsFileName: String = {
    if (isDataStreamSupported) {
      "/ror_audit/enabled_auditing_tools/readonlyrest.yml"
    } else {
      "/ror_audit/enabled_auditing_tools/readonlyrest_audit_index.yml"
    }
  }

  override def nodeDataInitializer: Option[ElasticsearchNodeDataInitializer] = Some(ElasticsearchTweetsInitializer)

  override lazy val destNodesClientProviders: NonEmptyList[ClientProvider] = NonEmptyList.of(this)

  override def baseRorSettingsYaml: String = resolvedRorSettingsFile.contentAsString

  override protected def baseAuditDataStreamName: Option[String] =
    Option.when(Version.greaterOrEqualThan(esVersionUsed, 7, 9, 0))("audit_data_stream")

  // Adding the ES cluster fields is disabled in the /enabled_auditing_tools/readonlyrest.yml settings file (`DefaultAuditLogSerializerV1` is used)
  override def assertForEveryAuditEntry(entry: JSON): Unit = {
    entry.obj.get("es_node_name") should be (None)
    entry.obj.get("es_cluster_name") should be (None)
  }

  "ES" should {
    "submit audit entries" when {
      "first request uses V1 serializer, then ROR settings is reloaded and second request uses V2 serializer" in {
        val indexManager = new IndexManager(basicAuthClient("username", "dev"), esVersionUsed)

        updateRorSettingsToUseSerializer("tech.beshu.ror.audit.instances.DefaultAuditLogSerializerV1")
        performAndAssertExampleSearchRequest(indexManager)

        updateRorSettingsToUseSerializer("tech.beshu.ror.audit.instances.DefaultAuditLogSerializerV2")
        performAndAssertExampleSearchRequest(indexManager)

        forEachAuditManager { adminAuditManager =>
          eventually {
            val auditEntries = adminAuditManager.getEntries.force().jsons

            // On Linux we could assert number of entries equal to 2.
            // On Windows reloading settings sometimes takes a little longer,
            // and there are 3 or more messages (from before reload, so not important)
            auditEntries.size should be >= 2

            val expectedV1Entry = ujson.read(
              """{
                |  "final_state": "ALLOWED",
                |  "user": "username",
                |  "logged_user": "username",
                |  "presented_identity": "username",
                |  "block": "{ name: 'Rule 1', policy: ALLOW, rules: [auth_key, methods, indices] }",
                |  "matched_block_names": ["Rule 1"],
                |  "acl_history": "[CONTAINER ADMIN: NOT_MATCHED (AUTH_FAIL (Username mismatch)) -> RULES:[auth_key->false]], [Rule 1: MATCHED -> RULES:[auth_key->true, methods->true, indices->true] RESOLVED:[user=username;indices=twitter]]",
                |  "blocks_history": [
                |    {
                |      "forbidden_cause": "AUTH_FAIL (Username mismatch)",
                |      "block_name": "CONTAINER ADMIN",
                |      "matched": false
                |    },
                |    {
                |      "block_name": "Rule 1",
                |      "matched": true
                |    }
                |  ],
                |  "match": true,
                |  "req_method": "GET",
                |  "type": "GetIndexRequest",
                |  "path": "/twitter/",
                |  "indices": ["twitter"],
                |  "content_len_kb": 0,
                |  "action": "indices:admin/get",
                |  "content_len": 0,
                |  "@timestamp": "ignored"
                |}""".stripMargin
            )
            auditEntries should containJsonMatching("@timestamp", "task_id", "processingMillis", "correlation_id", "id", "origin", "destination", "headers")(expectedV1Entry)

            val expectedV2Entry = ujson.read(
              """{
                |  "final_state": "ALLOWED",
                |  "user": "username",
                |  "logged_user": "username",
                |  "presented_identity": "username",
                |  "block": "{ name: 'Rule 1', policy: ALLOW, rules: [auth_key, methods, indices] }",
                |  "matched_block_names": ["Rule 1"],
                |  "es_node_name": "ROR_SINGLE_1",
                |  "es_cluster_name": "ROR_SINGLE",
                |  "acl_history": "[CONTAINER ADMIN: NOT_MATCHED (AUTH_FAIL (Username mismatch)) -> RULES:[auth_key->false]], [Rule 1: MATCHED -> RULES:[auth_key->true, methods->true, indices->true] RESOLVED:[user=username;indices=twitter]]",
                |  "blocks_history": [
                |    {
                |      "forbidden_cause": "AUTH_FAIL (Username mismatch)",
                |      "block_name": "CONTAINER ADMIN",
                |      "matched": false
                |    },
                |    {
                |      "block_name": "Rule 1",
                |      "matched": true
                |    }
                |  ],
                |  "match": true,
                |  "req_method": "GET",
                |  "type": "GetIndexRequest",
                |  "path": "/twitter/",
                |  "indices": ["twitter"],
                |  "content_len_kb": 0,
                |  "action": "indices:admin/get",
                |  "content_len": 0,
                |  "@timestamp": "ignored"
                |}""".stripMargin
            )
            auditEntries should containJsonMatching("@timestamp", "task_id", "processingMillis", "correlation_id", "id", "origin", "destination", "headers")(expectedV2Entry)
          }
        }
        updateRorSettingsToUseSerializer("tech.beshu.ror.audit.instances.DefaultAuditLogSerializerV1")
      }
      "using ReportingAllEventsAuditLogSerializer" in {
        val indexManager = new IndexManager(basicAuthClient("username", "dev"), esVersionUsed)

        updateRorSettingsToUseSerializer("tech.beshu.ror.audit.instances.FullAuditLogSerializer")
        performAndAssertExampleSearchRequest(indexManager)

        forEachAuditManager { adminAuditManager =>
          eventually {
            val auditEntries = adminAuditManager.getEntries.force().jsons
            assert(auditEntries.size >= 3)

            val expectedMainEntry = ujson.read(
              """{
                |  "final_state": "ALLOWED",
                |  "user": "username",
                |  "logged_user": "username",
                |  "presented_identity": "username",
                |  "block": "{ name: 'Rule 1', policy: ALLOW, rules: [auth_key, methods, indices] }",
                |  "matched_block_names": ["Rule 1"],
                |  "es_node_name": "ROR_SINGLE_1",
                |  "es_cluster_name": "ROR_SINGLE",
                |  "acl_history": "[CONTAINER ADMIN: NOT_MATCHED (AUTH_FAIL (Username mismatch)) -> RULES:[auth_key->false]], [Rule 1: MATCHED -> RULES:[auth_key->true, methods->true, indices->true] RESOLVED:[user=username;indices=twitter]]",
                |  "blocks_history": [
                |    {
                |      "forbidden_cause": "AUTH_FAIL (Username mismatch)",
                |      "block_name": "CONTAINER ADMIN",
                |      "matched": false
                |    },
                |    {
                |      "block_name": "Rule 1",
                |      "matched": true
                |    }
                |  ],
                |  "match": true,
                |  "req_method": "GET",
                |  "type": "GetIndexRequest",
                |  "path": "/twitter/",
                |  "indices": ["twitter"],
                |  "content_len_kb": 0,
                |  "action": "indices:admin/get",
                |  "content_len": 0,
                |  "@timestamp": "ignored"
                |}""".stripMargin
            )
            auditEntries should containJsonMatching("@timestamp", "task_id", "processingMillis", "correlation_id", "id", "origin", "destination", "headers")(expectedMainEntry)

            val expectedRefreshConfigEntry = ujson.read(
              """{
                |  "final_state": "ALLOWED",
                |  "user": "admin",
                |  "logged_user": "admin",
                |  "presented_identity": "admin",
                |  "block": "{ name: 'CONTAINER ADMIN', policy: ALLOW, rules: [auth_key] }",
                |  "matched_block_names": ["CONTAINER ADMIN"],
                |  "es_node_name": "ROR_SINGLE_1",
                |  "es_cluster_name": "ROR_SINGLE",
                |  "acl_history": "[CONTAINER ADMIN: MATCHED -> RULES:[auth_key->true] RESOLVED:[user=admin]]",
                |  "blocks_history": [
                |    {
                |      "block_name": "CONTAINER ADMIN",
                |      "matched": true
                |    }
                |  ],
                |  "match": true,
                |  "req_method": "POST",
                |  "type": "RRAdminRequest",
                |  "path": "/_readonlyrest/admin/refreshconfig/",
                |  "indices": [],
                |  "content_len_kb": 0,
                |  "action": "cluster:internal_ror/config/refreshsettings",
                |  "content_len": 0,
                |  "@timestamp": "ignored"
                |}""".stripMargin
            )
            auditEntries should containJsonMatching("@timestamp", "task_id", "processingMillis", "correlation_id", "id", "origin", "destination", "headers")(expectedRefreshConfigEntry)

            val expectedSearchEntry = ujson.read(
              """{
                |  "final_state": "ALLOWED",
                |  "user": "admin",
                |  "logged_user": "admin",
                |  "presented_identity": "admin",
                |  "block": "{ name: 'CONTAINER ADMIN', policy: ALLOW, rules: [auth_key] }",
                |  "matched_block_names": ["CONTAINER ADMIN"],
                |  "es_node_name": "ROR_SINGLE_1",
                |  "es_cluster_name": "ROR_SINGLE",
                |  "acl_history": "[CONTAINER ADMIN: MATCHED -> RULES:[auth_key->true] RESOLVED:[user=admin;indices=audit_index]]",
                |  "blocks_history": [
                |    {
                |      "block_name": "CONTAINER ADMIN",
                |      "matched": true
                |    }
                |  ],
                |  "match": true,
                |  "req_method": "POST",
                |  "type": "SearchRequest",
                |  "path": "/audit_index/_search/",
                |  "indices": ["audit_index"],
                |  "content_len_kb": 0,
                |  "action": "indices:data/read/search",
                |  "content_len": 0,
                |  "@timestamp": "ignored"
                |}""".stripMargin
            )
            auditEntries should containJsonMatching("@timestamp", "task_id", "processingMillis", "correlation_id", "id", "origin", "destination", "headers")(expectedSearchEntry)
          }
        }
        updateRorSettingsToUseSerializer("tech.beshu.ror.audit.instances.DefaultAuditLogSerializerV1")
        // This test uses serializer, that reports all events. We need to wait a moment, to ensure that there will be no more events using that serializer
        Thread.sleep(3000)
      }
      "using ReportingAllEventsWithQueryAuditLogSerializer" in {
        val indexManager = new IndexManager(basicAuthClient("username", "dev"), esVersionUsed)

        updateRorSettingsToUseSerializer("tech.beshu.ror.audit.instances.FullAuditLogWithQuerySerializer")
        performAndAssertExampleSearchRequest(indexManager)

        forEachAuditManager { adminAuditManager =>
          eventually {
            val auditEntries = adminAuditManager.getEntries.force().jsons
            assert(auditEntries.size >= 3)

            val expectedMainEntry = ujson.read(
              """{
                |  "final_state": "ALLOWED",
                |  "user": "username",
                |  "logged_user": "username",
                |  "presented_identity": "username",
                |  "block": "{ name: 'Rule 1', policy: ALLOW, rules: [auth_key, methods, indices] }",
                |  "matched_block_names": ["Rule 1"],
                |  "es_node_name": "ROR_SINGLE_1",
                |  "es_cluster_name": "ROR_SINGLE",
                |  "acl_history": "[CONTAINER ADMIN: NOT_MATCHED (AUTH_FAIL (Username mismatch)) -> RULES:[auth_key->false]], [Rule 1: MATCHED -> RULES:[auth_key->true, methods->true, indices->true] RESOLVED:[user=username;indices=twitter]]",
                |  "blocks_history": [
                |    {
                |      "forbidden_cause": "AUTH_FAIL (Username mismatch)",
                |      "block_name": "CONTAINER ADMIN",
                |      "matched": false
                |    },
                |    {
                |      "block_name": "Rule 1",
                |      "matched": true
                |    }
                |  ],
                |  "match": true,
                |  "req_method": "GET",
                |  "type": "GetIndexRequest",
                |  "path": "/twitter/",
                |  "indices": ["twitter"],
                |  "content_len_kb": 0,
                |  "action": "indices:admin/get",
                |  "content_len": 0,
                |  "content": "",
                |  "@timestamp": "ignored"
                |}""".stripMargin
            )
            auditEntries should containJsonMatching("@timestamp", "task_id", "processingMillis", "correlation_id", "id", "origin", "destination", "headers")(expectedMainEntry)

            val expectedRefreshConfigEntry = ujson.read(
              """{
                |  "final_state": "ALLOWED",
                |  "user": "admin",
                |  "logged_user": "admin",
                |  "presented_identity": "admin",
                |  "block": "{ name: 'CONTAINER ADMIN', policy: ALLOW, rules: [auth_key] }",
                |  "matched_block_names": ["CONTAINER ADMIN"],
                |  "es_node_name": "ROR_SINGLE_1",
                |  "es_cluster_name": "ROR_SINGLE",
                |  "acl_history": "[CONTAINER ADMIN: MATCHED -> RULES:[auth_key->true] RESOLVED:[user=admin]]",
                |  "blocks_history": [
                |    {
                |      "block_name": "CONTAINER ADMIN",
                |      "matched": true
                |    }
                |  ],
                |  "match": true,
                |  "req_method": "POST",
                |  "type": "RRAdminRequest",
                |  "path": "/_readonlyrest/admin/refreshconfig/",
                |  "indices": [],
                |  "content": "",
                |  "content_len_kb": 0,
                |  "action": "cluster:internal_ror/config/refreshsettings",
                |  "content_len": 0,
                |  "@timestamp": "ignored"
                |}""".stripMargin
            )
            auditEntries should containJsonMatching("@timestamp", "task_id", "processingMillis", "correlation_id", "id", "origin", "destination", "headers")(expectedRefreshConfigEntry)

            val expectedSearchEntry = ujson.read(
              """{
                |  "final_state": "ALLOWED",
                |  "user": "admin",
                |  "logged_user": "admin",
                |  "presented_identity": "admin",
                |  "block": "{ name: 'CONTAINER ADMIN', policy: ALLOW, rules: [auth_key] }",
                |  "matched_block_names": ["CONTAINER ADMIN"],
                |  "es_node_name": "ROR_SINGLE_1",
                |  "es_cluster_name": "ROR_SINGLE",
                |  "acl_history": "[CONTAINER ADMIN: MATCHED -> RULES:[auth_key->true] RESOLVED:[user=admin;indices=audit_index]]",
                |  "blocks_history": [
                |    {
                |      "block_name": "CONTAINER ADMIN",
                |      "matched": true
                |    }
                |  ],
                |  "match": true,
                |  "req_method": "POST",
                |  "type": "SearchRequest",
                |  "path": "/audit_index/_search/",
                |  "indices": ["audit_index"],
                |  "content": "",
                |  "content_len_kb": 0,
                |  "action": "indices:data/read/search",
                |  "content_len": 0,
                |  "@timestamp": "ignored"
                |}""".stripMargin
            )
            auditEntries should containJsonMatching("@timestamp", "task_id", "processingMillis", "correlation_id", "id", "origin", "destination", "headers")(expectedSearchEntry)
          }
        }
        updateRorSettingsToUseSerializer("tech.beshu.ror.audit.instances.DefaultAuditLogSerializerV1")
        // This test uses serializer, that reports all events. We need to wait a moment, to ensure that there will be no more events using that serializer
        Thread.sleep(3000)
      }
      "using ConfigurableQueryAuditLogSerializer" in {
        val indexManager = new IndexManager(basicAuthClient("username", "dev"), esVersionUsed)

        // Change setting to use configurable serializer and perform the request
        updateRorSettings(
          originalString = """type: "static"""",
          newString = """type: "configurable"""",
        )
        performAndAssertExampleSearchRequest(indexManager)

        // Assert, that there is a single audit entry
        forEachAuditManager { adminAuditManager =>
          eventually {
            val auditEntries = adminAuditManager.getEntries.force().jsons
            auditEntries.size should be (1)

            val expectedEntry = ujson.read(
              """{
                |  "block": "{ name: 'Rule 1', policy: ALLOW, rules: [auth_key, methods, indices] }",
                |  "node_name_with_static_suffix": "ROR_SINGLE_1 with suffix",
                |  "another_field": "ROR_SINGLE GET",
                |  "tid": 0,
                |  "bytes": 0,
                |  "@timestamp": "ignored"
                |}""".stripMargin
            )
            auditEntries should containJsonMatching("@timestamp", "tid")(expectedEntry)
          }
        }

        // Disable audit for Rule 1, clean managers, perform second request
        updateRorSettings(
          "enabled: true ## twitter audit toggle",
          "enabled: false ## twitter audit toggle",
        )
        adminAuditManagers.values.foreach(_.head.truncate())
        performAndAssertExampleSearchRequest(indexManager)

        // Wait for 2s and assert, that there is no serialized event
        Thread.sleep(2000)
        forEachAuditManager { adminAuditManager =>
          val auditEntries = adminAuditManager.getEntries.force().jsons
          auditEntries.size should be (0)
        }

        // Restore the default settings
        updateRorSettings(
          "enabled: false ## twitter audit toggle",
          "enabled: true ## twitter audit toggle",
        )
        updateRorSettingsToUseSerializer("tech.beshu.ror.audit.instances.DefaultAuditLogSerializerV1")
      }
      "using ECS serializer" in {
        val indexManager = new IndexManager(basicAuthClient("username", "dev"), esVersionUsed)
        // We need to create a new index with a different name for this test, because the ECS schema
        // is not compatible with the Json object created by other serializers in previous tests.
        val ecsAuditIndexName = "ecs_audit_index"
        updateRorSettings(
          replacements = Map(
            """type: "static"""" -> """type: "ecs"""",
            "audit_index" -> ecsAuditIndexName,
          )
        )
        val auditIndexManager = new AuditIndexManager(destNodeClientProvider.adminClient, esVersionUsed, ecsAuditIndexName)
        performAndAssertExampleSearchRequest(indexManager)
        eventually {
          val auditEntries = auditIndexManager.getEntries.force().jsons
          val expectedAuditEntry = ujson.read(
            """{
              |  "ecs": { "version": "1.6.0" },
              |  "trace": { "id": "ignored" },
              |  "@timestamp": "ignored",
              |  "destination": { "address": "ignored" },
              |  "source": { "address": "ignored" },
              |  "http": {
              |    "request": {
              |      "method": "GET",
              |      "body": { "bytes": 0 }
              |    }
              |  },
              |  "event": {
              |    "id": "ignored",
              |    "duration": 0,
              |    "action": "indices:admin/get",
              |    "reason": "GetIndexRequest",
              |    "outcome": "success"
              |  },
              |  "error": {},
              |  "user": {
              |    "name": "username",
              |    "effective": {}
              |  },
              |  "url": { "path": "/twitter/" },
              |  "labels": {
              |    "es_cluster_name": "ROR_SINGLE",
              |    "es_node_name": "ROR_SINGLE_1",
              |    "es_task_id": 0,
              |    "ror_involved_indices": ["twitter"],
              |    "ror_acl_history": "[CONTAINER ADMIN: NOT_MATCHED (AUTH_FAIL (Username mismatch)) -> RULES:[auth_key->false]], [Rule 1: MATCHED -> RULES:[auth_key->true, methods->true, indices->true] RESOLVED:[user=username;indices=twitter]]",
              |    "ror_final_state": "ALLOWED",
              |    "ror_detailed_reason": "{ name: 'Rule 1', policy: ALLOW, rules: [auth_key, methods, indices] }",
              |    "ror_matched_block_names": ["Rule 1"]
              |  }
              |}""".stripMargin
          )

          auditEntries should containJsonMatching(
            "@timestamp",
            "trace.id",
            "destination.address",
            "source.address",
            "event.id",
            "event.duration",
            "labels.es_task_id"
          )(expectedAuditEntry)
        }
        updateRorSettingsToUseSerializer("tech.beshu.ror.audit.instances.DefaultAuditLogSerializerV1")
      }
    }
  }

  private def performAndAssertExampleSearchRequest(indexManager: IndexManager) = {
    val response = indexManager.getIndex("twitter")
    response should have statusCode 200
  }

  private def updateRorSettingsToUseSerializer(serializer: String): Unit =
    updateRorSettings(
      originalString = """class_name: "tech.beshu.ror.audit.instances.DefaultAuditLogSerializerV1"""",
      newString = s"""class_name: "$serializer""""
    )

  private def updateRorSettings(originalString: String, newString: String): Unit =
    updateRorSettings(Map(originalString -> newString))

  private def updateRorSettings(replacements: Map[String, String]): Unit = {
    val initialSettings = getResourceContent(rorSettingsFileName)
    val modifiedSettings = replacements.foldLeft(initialSettings) { case (soFar, (originalString, newString)) =>
      soFar.replace(originalString, newString)
    }
    rorApiManager.updateRorInIndexSettings(modifiedSettings).forceOKStatusOrSettingsAlreadyLoaded()
    rorApiManager.reloadRorSettings().force()
  }
}
