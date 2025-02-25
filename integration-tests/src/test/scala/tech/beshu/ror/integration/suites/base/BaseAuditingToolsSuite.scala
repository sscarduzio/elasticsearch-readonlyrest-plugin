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
package tech.beshu.ror.integration.suites.base

import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.integration.suites.base.support.SingleClientSupport
import tech.beshu.ror.integration.utils.ESVersionSupportForAnyWordSpecLike
import tech.beshu.ror.utils.containers.EsClusterProvider
import tech.beshu.ror.utils.containers.providers.ClientProvider
import tech.beshu.ror.utils.elasticsearch.BaseTemplateManager.Template
import tech.beshu.ror.utils.elasticsearch.ComponentTemplateManager.ComponentTemplate
import tech.beshu.ror.utils.elasticsearch.{AuditIndexManager, ComponentTemplateManager, DataStreamManager, IndexLifecycleManager, IndexManager, IndexTemplateManager, RorApiManager}
import tech.beshu.ror.utils.misc.{CustomScalaTestMatchers, Version}
import tech.beshu.ror.utils.misc.Resources.getResourceContent

import java.util.UUID

trait BaseAuditingToolsSuite
  extends AnyWordSpec
    with ESVersionSupportForAnyWordSpecLike
    with SingleClientSupport
    with BeforeAndAfterEach
    with CustomScalaTestMatchers
    with Eventually {
  this: EsClusterProvider =>

  protected def destNodeClientProvider: ClientProvider

  protected def baseRorConfig: String

  protected def baseAuditDataStreamName: Option[String]

  private lazy val baseAuditIndexName = "audit_index"

  private lazy val adminAuditManagers =
    (List(baseAuditIndexName) ++ baseAuditDataStreamName.toList)
      .map { indexName =>
        (indexName, new AuditIndexManager(destNodeClientProvider.adminClient, esVersionUsed, indexName))
      }
      .toMap

  override def beforeEach(): Unit = {
    super.beforeEach()
    adminAuditManagers.values.foreach(_.truncate())
  }

  private def forEachAuditManager[A](test: => AuditIndexManager => A): Unit =
    adminAuditManagers.foreach { case (indexName, manager) =>
      withClue(s"Error for audit index '$indexName'") {
        test(manager)
      }
    }

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(15, Seconds)), interval = scaled(Span(100, Millis)))

  "Regular ES request" should {
    "be audited" when {
      "rule 1 is matched with logged user" in {
        val indexManager = new IndexManager(basicAuthClient("username", "dev"), esVersionUsed)
        val response = indexManager.getIndex("twitter")
        response should have statusCode 200

        forEachAuditManager { adminAuditManager =>
          eventually {
            val auditEntries = adminAuditManager.getEntries.force().jsons
            auditEntries.size shouldBe 1
            val firstEntry = auditEntries(0)
            firstEntry("final_state").str shouldBe "ALLOWED"
            firstEntry("user").str shouldBe "username"
            firstEntry("block").str.contains("name: 'Rule 1'") shouldBe true
          }
        }
      }
      "no rule is matched with username from auth header" in {
        val indexManager = new IndexManager(
          basicAuthClient("username", "wrong"), esVersionUsed
        )
        val response = indexManager.getIndex("twitter")
        response should have statusCode 403

        forEachAuditManager { adminAuditManager =>
          eventually {
            val auditEntries = adminAuditManager.getEntries.jsons
            auditEntries.size shouldBe 1

            val firstEntry = auditEntries(0)
            firstEntry("final_state").str shouldBe "FORBIDDEN"
            firstEntry("user").str shouldBe "username"
          }
        }
      }
      "no rule is matched with raw auth header as user" in {
        val indexManager = new IndexManager(tokenAuthClient("user_token"), esVersionUsed)
        val response = indexManager.getIndex("twitter")
        response should have statusCode 403

        forEachAuditManager { adminAuditManager =>
          eventually {
            val auditEntries = adminAuditManager.getEntries.jsons
            auditEntries.size shouldBe 1

            val firstEntry = auditEntries(0)
            firstEntry("final_state").str shouldBe "FORBIDDEN"
            firstEntry("user").str shouldBe "user_token"
          }
        }
      }
      "rule 1 is matched" when {
        "two requests were sent and correlationId is the same for both of them" in {
          val correlationId = UUID.randomUUID().toString
          val indexManager = new IndexManager(
            basicAuthClient("username", "dev"),
            esVersionUsed,
            additionalHeaders = Map("x-ror-correlation-id" -> correlationId)
          )

          val response1 = indexManager.getIndex("twitter")
          response1 should have statusCode 200

          val response2 = indexManager.getIndex("twitter")
          response2 should have statusCode 200

          forEachAuditManager { adminAuditManager =>
            eventually {
              val auditEntries = adminAuditManager.getEntries.jsons
              auditEntries.size shouldBe 2

              auditEntries(0)("correlation_id").str shouldBe correlationId
              auditEntries(1)("correlation_id").str shouldBe correlationId
            }
          }
        }
        "two requests were sent and the first one is user metadata request" in {
          val userMetadataManager = new RorApiManager(basicAuthClient("username", "dev"), esVersionUsed)
          val userMetadataResponse = userMetadataManager.fetchMetadata()

          userMetadataResponse should have statusCode 200
          val correlationId = userMetadataResponse.responseJson("x-ror-correlation-id").str

          val indexManager = new IndexManager(
            basicAuthClient("username", "dev"),
            esVersionUsed,
            additionalHeaders = Map("x-ror-correlation-id" -> correlationId)
          )

          val getIndexResponse = indexManager.getIndex("twitter")
          getIndexResponse should have statusCode 200

          forEachAuditManager { adminAuditManager =>
            eventually {
              val auditEntries = adminAuditManager.getEntries.jsons
              auditEntries.size shouldBe 2

              auditEntries(0)("correlation_id").str shouldBe correlationId
              auditEntries(1)("correlation_id").str shouldBe correlationId
            }
          }
        }
        "two metadata requests were sent, one with correlationId" in {
          def fetchMetadata(correlationId: Option[String]) = {
            val userMetadataManager = new RorApiManager(
              client = basicAuthClient("username", "dev"),
              esVersion = esVersionUsed
            )
            userMetadataManager.fetchMetadata(correlationId = correlationId)
          }

          val correlationId = UUID.randomUUID().toString
          val response1 = fetchMetadata(correlationId = Some(correlationId))
          response1 should have statusCode 200
          val loggingId1 = response1.responseJson("x-ror-correlation-id").str

          val response2 = fetchMetadata(correlationId = Some(correlationId))
          response2 should have statusCode 200
          val loggingId2 = response2.responseJson("x-ror-correlation-id").str

          loggingId1 should be(loggingId2)

          forEachAuditManager { adminAuditManager =>
            eventually {
              val auditEntries = adminAuditManager.getEntries.jsons
              auditEntries.size shouldBe 2

              auditEntries.map(_("correlation_id").str).toSet shouldBe Set(loggingId1, loggingId2)
            }
          }
        }
      }
    }
    "not be audited" when {
      "rule 2 is matched" in {
        val indexManager = new IndexManager(basicAuthClient("username", "dev"), esVersionUsed)
        val response = indexManager.getIndex("facebook")
        response should have statusCode 200

        forEachAuditManager { adminAuditManager =>
          eventually {
            adminAuditManager.hasNoEntries
          }
        }
      }
    }
  }

  "ROR audit event request" should {
    "be audited" when {
      "rule 3 is matched" when {
        "no JSON key attribute from request body payload is defined in audit serializer" in {
          val rorApiManager = new RorApiManager(basicAuthClient("username", "dev"), esVersionUsed)

          val response = rorApiManager.sendAuditEvent(ujson.read("""{ "event": "logout" }""")).force()

          response should have statusCode 204

          forEachAuditManager { adminAuditManager =>
            eventually {
              val auditEntries = adminAuditManager.getEntries.jsons
              auditEntries.size should be(1)
              auditEntries(0)("event").str should be("logout")
            }
          }
        }
        "user JSON key attribute from request doesn't override the defined in audit serializer" in {
          val rorApiManager = new RorApiManager(basicAuthClient("username", "dev"), esVersionUsed)

          val response = rorApiManager.sendAuditEvent(ujson.read("""{ "user": "unknown" }"""))

          response should have statusCode 204

          forEachAuditManager { adminAuditManager =>
            eventually {
              val auditEntries = adminAuditManager.getEntries.jsons
              auditEntries.size should be(1)
              auditEntries(0)("user").str should be("username")
            }
          }
        }
        "new JSON key attribute from request body as a JSON value" in {
          val rorApiManager = new RorApiManager(basicAuthClient("username", "dev"), esVersionUsed)

          val response = rorApiManager.sendAuditEvent(ujson.read("""{ "event_obj": { "field1": 1, "fields2": "f2" } }"""))

          response should have statusCode 204

          forEachAuditManager { adminAuditManager =>
            eventually {
              val auditEntries = adminAuditManager.getEntries.jsons
              auditEntries.size should be(1)
              auditEntries(0)("event_obj") should be(ujson.read("""{ "field1": 1, "fields2": "f2" }"""))
            }
          }
        }
      }
    }
    "not be audited" when {
      "admin rule is matched" in {
        val rorApiManager = new RorApiManager(adminClient, esVersionUsed)

        val response = rorApiManager.sendAuditEvent(ujson.read("""{ "event": "logout" }"""))
        response should have statusCode 204

        forEachAuditManager { adminAuditManager =>
          eventually {
            adminAuditManager.hasNoEntries
          }
        }
      }
      "request JSON is malformed" in {
        val rorApiManager = new RorApiManager(basicAuthClient("username", "dev"), esVersionUsed)

        val response = rorApiManager.sendAuditEvent(ujson.read("""[]"""))
        response should have statusCode 400
        response.responseJson should be(ujson.read(
          """
            |{
            |  "error":{
            |    "root_cause":[
            |      {
            |        "type":"audit_event_bad_request",
            |        "reason":"Content malformed"
            |      }
            |    ],
            |    "type":"audit_event_bad_request",
            |    "reason":"Content malformed"
            |  },
            |  "status":400
            |}
          """.stripMargin))

        forEachAuditManager { adminAuditManager =>
          eventually {
            adminAuditManager.hasNoEntries
          }
        }
      }
      "request JSON is too large (>5KB)" in {
        val rorApiManager = new RorApiManager(basicAuthClient("username", "dev"), esVersionUsed)

        val response = rorApiManager.sendAuditEvent(ujson.read(s"""{ "event": "${LazyList.continually("!").take(5000).mkString}" }"""))
        response should have statusCode 413
        response.responseJson should be(ujson.read(
          """
            |{
            |  "error":{
            |    "root_cause":[
            |      {
            |        "type":"audit_event_request_payload_too_large",
            |        "reason":"Max request content allowed = 40.0KB"
            |      }
            |    ],
            |    "type":"audit_event_request_payload_too_large",
            |    "reason":"Max request content allowed = 40.0KB"
            |  },
            |  "status":413
            |}
          """.stripMargin))

        forEachAuditManager { adminAuditManager =>
          eventually {
            adminAuditManager.hasNoEntries
          }
        }
      }
    }
  }

  "ROR audit index setup" should {
    "create an index if not exist" in {
      val initialConfig = getResourceContent("/ror_audit/disabled_auditing_tools/readonlyrest.yml")
      val rorApiManager = new RorApiManager(adminClient, esVersionUsed)
      rorApiManager.updateRorInIndexConfig(initialConfig).forceOkStatus()

      val newIndex = s"audit-index-${UUID.randomUUID().toString}"
      rorApiManager.updateRorInIndexConfig(rorConfigWithIndexAudit(newIndex)).forceOkStatus()

      val indexManager = new IndexManager(basicAuthClient("username", "dev"), esVersionUsed)
      val indexResponse = indexManager.getIndex("twitter")
      indexResponse should have statusCode 200

      val adminAuditManager = new AuditIndexManager(destNodeClientProvider.adminClient, esVersionUsed, newIndex)

      eventually {
        val auditEntries = adminAuditManager.getEntries.force().jsons
        auditEntries.size shouldBe 1

        val firstEntry = auditEntries(0)
        firstEntry("final_state").str shouldBe "ALLOWED"
        firstEntry("user").str shouldBe "username"
        firstEntry("block").str.contains("name: 'Rule 1'") shouldBe true
      }

      assertDynamicIndexMappings(newIndex)
    }
  }

  "ROR audit data stream setup" should {
    "create an audit data stream if not exist" excludeES(allEs6x, allEs7xBelowEs79x) in {
      val initialConfig = getResourceContent("/ror_audit/disabled_auditing_tools/readonlyrest.yml")
      val rorApiManager = new RorApiManager(adminClient, esVersionUsed)
      rorApiManager.updateRorInIndexConfig(initialConfig).forceOkStatus()

      val newDataStream = s"audit-ds-${UUID.randomUUID().toString}"
      val dataStreamManager = new DataStreamManager(destNodeClientProvider.adminClient, esVersionUsed)

      val response = dataStreamManager.getAllDataStreams()
      response.force().allDataStreams should not contain (newDataStream)

      rorApiManager.updateRorInIndexConfig(rorConfigWithDataStreamAudit(newDataStream)).forceOkStatus()

      eventually {
        val response = dataStreamManager.getAllDataStreams()
        response.force().allDataStreams should contain(newDataStream)
      }

      val indexManager = new IndexManager(basicAuthClient("username", "dev"), esVersionUsed)
      val indexResponse = indexManager.getIndex("twitter")
      indexResponse should have statusCode 200
      val adminAuditManager = new AuditIndexManager(destNodeClientProvider.adminClient, esVersionUsed, newDataStream)
      eventually {
        val auditEntries = adminAuditManager.getEntries.force().jsons
        auditEntries.size shouldBe 1

        val firstEntry = auditEntries(0)
        firstEntry("final_state").str shouldBe "ALLOWED"
        firstEntry("user").str shouldBe "username"
        firstEntry("block").str.contains("name: 'Rule 1'") shouldBe true
      }

      assertAuditDataStreamSettings(newDataStream)
    }
    "use existing data stream" excludeES(allEs6x, allEs7xBelowEs79x) in {
      val initialConfig = getResourceContent("/ror_audit/disabled_auditing_tools/readonlyrest.yml")
      val rorApiManager = new RorApiManager(adminClient, esVersionUsed)
      rorApiManager.updateRorInIndexConfig(initialConfig).forceOkStatus()

      val dataStreamName = s"audit-ds-${UUID.randomUUID().toString}"
      createAuditDataStream(dataStreamName)

      rorApiManager.updateRorInIndexConfig(rorConfigWithDataStreamAudit(dataStreamName)).forceOkStatus()

      val indexManager = new IndexManager(basicAuthClient("username", "dev"), esVersionUsed)
      val indexResponse = indexManager.getIndex("twitter")
      indexResponse should have statusCode 200

      val adminAuditManager = new AuditIndexManager(destNodeClientProvider.adminClient, esVersionUsed, dataStreamName)
      eventually {
        val auditEntries = adminAuditManager.getEntries.force().jsons
        auditEntries.size shouldBe 1

        val firstEntry = auditEntries(0)
        firstEntry("final_state").str shouldBe "ALLOWED"
        firstEntry("user").str shouldBe "username"
        firstEntry("block").str.contains("name: 'Rule 1'") shouldBe true
      }
    }
  }

  private def rorConfigWithIndexAudit(indexName: String) = {
    baseRorConfig.replace(
      baseAuditIndexName,
      indexName
    )
  }

  private def rorConfigWithDataStreamAudit(dataStreamName: String) = {
    baseRorConfig.replace(
      baseAuditDataStreamName.getOrElse(throw new IllegalStateException("Data stream name should be set for Data Stream audit test")),
      dataStreamName
    )
  }

  private def createAuditDataStream(dataStreamName: String) = {
    val templateManager = new IndexTemplateManager(destNodeClientProvider.adminClient, esVersionUsed)
    templateManager.createTemplate(
      templateName = s"$dataStreamName-template",
      body = ujson.read(
        s"""
           |{
           |  "index_patterns": ["$dataStreamName*"],
           |  "data_stream": { },
           |  "priority": 500,
           |  "template": {
           |    "mappings": {
           |      "properties": {
           |        "@timestamp": {
           |          "type": "date",
           |          "format": "date_optional_time||epoch_millis"
           |        }
           |      }
           |    }
           |  }
           |}
           |""".stripMargin
      )
    ).force()

    val dataStreamManager = new DataStreamManager(destNodeClientProvider.adminClient, esVersionUsed)
    dataStreamManager.createDataStream(dataStreamName).force()
  }

  private def assertAuditDataStreamSettings(dataStreamName: String): Unit = {
    val indexLifecycleManager = new IndexLifecycleManager(destNodeClientProvider.adminClient, esVersionUsed)
    val policyName = s"$dataStreamName-lifecycle-policy"
    val settingsName = s"$dataStreamName-settings"
    val mappingsName = s"$dataStreamName-mappings"
    val indexTemplateName = s"$dataStreamName-template"

    val indexLifecycle = indexLifecycleManager.getPolicy(policyName)
    val policy = indexLifecycle.policies.get(policyName)

    val expectedPolicy = if (Version.greaterOrEqualThan(esVersionUsed, 8, 14, 0)) {
      ujson.read(
        s"""
           |{
           |  "policy":{
           |    "phases":{
           |      "hot":{
           |        "min_age":"0ms",
           |        "actions":{
           |          "rollover":{
           |            "max_age":"1d",
           |            "max_primary_shard_size":"50gb"
           |          }
           |        }
           |      },
           |      "warm":{
           |        "min_age":"14d",
           |        "actions":{
           |          "shrink":{
           |            "number_of_shards":1,
           |            "allow_write_after_shrink":false
           |          },
           |          "forcemerge":{
           |            "max_num_segments":1
           |          }
           |        }
           |      },
           |      "cold":{
           |        "min_age":"30d",
           |        "actions":{
           |          "freeze":{
           |
           |          }
           |        }
           |      }
           |    }
           |  }
           |}
           |
           |""".stripMargin
      )
    } else if (Version.greaterOrEqualThan(esVersionUsed, 7, 14, 0)) {
      ujson.read(
        s"""
           |{
           |  "policy":{
           |    "phases":{
           |      "hot":{
           |        "min_age":"0ms",
           |        "actions":{
           |          "rollover":{
           |            "max_age":"1d",
           |            "max_primary_shard_size":"50gb"
           |          }
           |        }
           |      },
           |      "warm":{
           |        "min_age":"14d",
           |        "actions":{
           |          "shrink":{
           |            "number_of_shards":1
           |          },
           |          "forcemerge":{
           |            "max_num_segments":1
           |          }
           |        }
           |      },
           |      "cold":{
           |        "min_age":"30d",
           |        "actions":{
           |          "freeze":{
           |
           |          }
           |        }
           |      }
           |    }
           |  }
           |}
           |
           |""".stripMargin
      )
    }
    else {
      ujson.read(
        s"""
           |{
           |  "policy":{
           |    "phases":{
           |      "hot":{
           |        "min_age":"0ms",
           |        "actions":{
           |          "rollover":{
           |            "max_age":"1d"
           |          }
           |        }
           |      },
           |      "warm":{
           |        "min_age":"14d",
           |        "actions":{
           |          "shrink":{
           |            "number_of_shards":1
           |          },
           |          "forcemerge":{
           |            "max_num_segments":1
           |          }
           |        }
           |      },
           |      "cold":{
           |        "min_age":"30d",
           |        "actions":{
           |          "freeze":{
           |
           |          }
           |        }
           |      }
           |    }
           |  }
           |}
           |
           |""".stripMargin
      )
    }

    policy shouldBe Some(expectedPolicy)

    val componentTemplateManager = new ComponentTemplateManager(destNodeClientProvider.adminClient, esVersionUsed)
    val settingsResponse = componentTemplateManager.getTemplate(settingsName)
    settingsResponse.templates.headOption shouldBe Some(ComponentTemplate(settingsName, aliases = Set.empty))
    val mappingsResponse = componentTemplateManager.getTemplate(mappingsName)
    mappingsResponse.templates.headOption shouldBe Some(ComponentTemplate(mappingsName, aliases = Set.empty))

    val templateManager = new IndexTemplateManager(destNodeClientProvider.adminClient, esVersionUsed)
    val templateResponse = templateManager.getTemplate(indexTemplateName)
    templateResponse.templates.headOption shouldBe Some(Template(indexTemplateName, patterns = Set(dataStreamName), aliases = Set.empty))

    val dataStreamManager = new DataStreamManager(destNodeClientProvider.adminClient, esVersionUsed)
    val dataStreamResponse = dataStreamManager.getDataStream(dataStreamName)
    dataStreamResponse.indexTemplateByDataStream(dataStreamName) shouldBe indexTemplateName
    dataStreamResponse.ilmPolicyByDataStream(dataStreamName) shouldBe policyName

    dataStreamResponse.backingIndices.foreach { backingIndex =>
      assertDynamicIndexMappings(backingIndex)
    }
  }

  private def assertDynamicIndexMappings(indexName: String) = {
    val indexManager = new IndexManager(destNodeClientProvider.adminClient, esVersionUsed)
    val expectedProperties = List(
      "@timestamp", "acl_history", "action", "block", "content_len", "content_len_kb",
      "correlation_id", "destination", "final_state", "headers", "id", "indices", "match",
      "origin", "path", "processingMillis", "req_method", "task_id", "type", "user"
    )
    val mappings = indexManager.getMappings(indexName).responseJson(indexName)("mappings").obj
    val properties = {
      if (Version.greaterOrEqualThan(esVersionUsed, 7,0,0)) {
        mappings("properties").obj.keySet
      } else {
        mappings("ror_audit_evt")("properties").obj.keySet
      }
    }

    properties should contain allElementsOf (expectedProperties)
  }
}
