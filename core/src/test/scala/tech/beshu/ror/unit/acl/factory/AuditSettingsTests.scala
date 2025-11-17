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
package tech.beshu.ror.unit.acl.factory

import cats.data.NonEmptyList
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.{Json, parser}
import io.lemonlabs.uri.Uri
import monix.execution.Scheduler.Implicits.global
import org.json.JSONObject
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.audit.AuditFieldUtils
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditSettings.AuditSink
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditSettings.AuditSink.Config
import tech.beshu.ror.accesscontrol.audit.configurable.ConfigurableAuditLogSerializer
import tech.beshu.ror.accesscontrol.audit.ecs.EcsV1AuditLogSerializer
import tech.beshu.ror.accesscontrol.blocks.mocks.NoOpMocksProvider
import tech.beshu.ror.accesscontrol.domain.AuditCluster.*
import tech.beshu.ror.accesscontrol.domain.{AuditCluster, IndexName, RorAuditLoggerName, RorConfigurationIndex}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.AuditingSettingsCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.{Core, RawRorConfigBasedCoreFactory}
import tech.beshu.ror.audit.*
import tech.beshu.ror.audit.AuditResponseContext.Verbosity
import tech.beshu.ror.audit.adapters.{DeprecatedAuditLogSerializerAdapter, EnvironmentAwareAuditLogSerializerAdapter}
import tech.beshu.ror.audit.instances.*
import tech.beshu.ror.audit.utils.AuditSerializationHelper.{AllowedEventMode, AuditFieldPath, AuditFieldValueDescriptor}
import tech.beshu.ror.configuration.{EnvironmentConfig, RawRorConfig, RorConfig}
import tech.beshu.ror.es.EsVersion
import tech.beshu.ror.mocks.{MockHttpClientsFactory, MockLdapConnectionPoolProvider}
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import java.time.{Instant, ZoneId, ZonedDateTime}
import scala.reflect.ClassTag

class AuditSettingsTests extends AnyWordSpec with Inside {

  private def factory(esVersion: EsVersion = defaultEsVersionForTests) = {
    implicit val environmentConfig: EnvironmentConfig = EnvironmentConfig.default
    new RawRorConfigBasedCoreFactory(esVersion)
  }

  private val zonedDateTime = ZonedDateTime.of(2019, 1, 1, 0, 1, 59, 0, ZoneId.of("+1"))

  "Audit settings" when {
    "audit is not configured" should {
      "be disabled by default" in {
        val config = rorConfigFromUnsafe(
          """
            |readonlyrest:
            |
            |  access_control_rules:
            |
            |  - name: test_block
            |    type: allow
            |    auth_key: admin:container
            |
          """.stripMargin)

        assertSettingsNoPresent(config)
      }
    }
    "audit is disabled" should {
      "be disabled" when {
        "one line audit format" in {
          val config = rorConfigFromUnsafe(
            """
              |readonlyrest:
              |  audit.enabled: false
              |
              |  access_control_rules:
              |
              |  - name: test_block
              |    type: allow
              |    auth_key: admin:container
              |
            """.stripMargin)

          assertSettingsNoPresent(config)
        }
        "multi line audit format" in {
          val config = rorConfigFromUnsafe(
            """
              |readonlyrest:
              |  audit:
              |    enabled: false
              |
              |  access_control_rules:
              |
              |  - name: test_block
              |    type: allow
              |    auth_key: admin:container
              |
           """.stripMargin)

          assertSettingsNoPresent(config)
        }
      }
    }
    "audit is enabled" should {
      "be able to be loaded from config" when {
        "no outputs defined" should {
          "fallback to default index based audit sink" when {
            "one line audit format" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit.enabled: true
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
                """.stripMargin)

              assertIndexBasedAuditSinkSettingsPresent[BlockVerbosityAwareAuditLogSerializer](
                config,
                expectedIndexName = "readonlyrest_audit-2018-12-31",
                expectedAuditCluster = LocalAuditCluster
              )
            }
            "multi line audit format" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit:
                  |    enabled: true
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
                """.stripMargin)

              assertIndexBasedAuditSinkSettingsPresent[BlockVerbosityAwareAuditLogSerializer](
                config,
                expectedIndexName = "readonlyrest_audit-2018-12-31",
                expectedAuditCluster = LocalAuditCluster
              )
            }
          }
        }
        "simple format is used" in {
          val config = rorConfigFromUnsafe(
            """
              |readonlyrest:
              |  audit:
              |    enabled: true
              |    outputs: [index, log, data_stream]
              |
              |  access_control_rules:
              |
              |  - name: test_block
              |    type: allow
              |    auth_key: admin:container
              |
            """.stripMargin)

          val core = factory()
            .createCoreFrom(
              config,
              RorConfigurationIndex(IndexName.Full(".readonlyrest")),
              MockHttpClientsFactory,
              MockLdapConnectionPoolProvider,
              NoOpMocksProvider
            )
            .runSyncUnsafe()
          inside(core) { case Right(Core(_, RorConfig(_, _, _, Some(auditingSettings)))) =>
            auditingSettings.auditSinks.size should be(3)

            val sink1 = auditingSettings.auditSinks.head
            sink1 shouldBe a[AuditSink.Enabled]
            val enabledSink1 = sink1.asInstanceOf[AuditSink.Enabled].config
            enabledSink1 shouldBe a[Config.EsIndexBasedSink]
            val sink1Config = enabledSink1.asInstanceOf[Config.EsIndexBasedSink]
            sink1Config.rorAuditIndexTemplate.indexName(zonedDateTime.toInstant) should be(indexName("readonlyrest_audit-2018-12-31"))
            sink1Config.logSerializer shouldBe a[BlockVerbosityAwareAuditLogSerializer]
            sink1Config.auditCluster shouldBe AuditCluster.LocalAuditCluster

            val sink2 = auditingSettings.auditSinks.toList(1)
            sink2 shouldBe a[AuditSink.Enabled]
            val enabledSink2 = sink2.asInstanceOf[AuditSink.Enabled].config
            enabledSink2 shouldBe a[Config.LogBasedSink]
            val sink2Config = enabledSink2.asInstanceOf[Config.LogBasedSink]
            sink2Config.loggerName should be(RorAuditLoggerName("readonlyrest_audit"))
            sink2Config.logSerializer shouldBe a[BlockVerbosityAwareAuditLogSerializer]

            val sink3 = auditingSettings.auditSinks.toList(2)
            sink3 shouldBe a[AuditSink.Enabled]
            val enabledSink3 = sink3.asInstanceOf[AuditSink.Enabled].config
            enabledSink3 shouldBe a[Config.EsDataStreamBasedSink]
            val sink3Config = enabledSink3.asInstanceOf[Config.EsDataStreamBasedSink]
            sink3Config.rorAuditDataStream.dataStream should be(fullDataStreamName("readonlyrest_audit"))
            sink3Config.logSerializer shouldBe a[BlockVerbosityAwareAuditLogSerializer]
            sink3Config.auditCluster shouldBe AuditCluster.LocalAuditCluster
          }
        }
        "'log' output type defined" when {
          "only type is set" in {
            val config = rorConfigFromUnsafe(
              """
                |readonlyrest:
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: log
                |
                |  access_control_rules:
                |
                |  - name: test_block
                |    type: allow
                |    auth_key: admin:container
                |
              """.stripMargin)

            assertLogBasedAuditSinkSettingsPresent[BlockVerbosityAwareAuditLogSerializer](
              config,
              expectedLoggerName = "readonlyrest_audit"
            )
          }
          "the output's enabled flag is set" when {
            "set to true" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: log
                  |      enabled: true
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
                """.stripMargin)

              assertLogBasedAuditSinkSettingsPresent[BlockVerbosityAwareAuditLogSerializer](
                config,
                expectedLoggerName = "readonlyrest_audit"
              )
            }
            "set to false" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: log
                  |      enabled: false
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
                """.stripMargin)

              assertSettings(
                config,
                expectedConfigs = NonEmptyList.of(AuditSink.Disabled)
              )
            }
          }
          "custom logger name is set" in {
            val config = rorConfigFromUnsafe(
              """
                |readonlyrest:
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: log
                |      logger_name: custom_logger
                |
                |  access_control_rules:
                |
                |  - name: test_block
                |    type: allow
                |    auth_key: admin:container
                |
              """.stripMargin)

            assertLogBasedAuditSinkSettingsPresent[BlockVerbosityAwareAuditLogSerializer](
              config,
              expectedLoggerName = "custom_logger"
            )
          }
          "custom serializer is set" in {
            val config = rorConfigFromUnsafe(
              """
                |readonlyrest:
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: log
                |      serializer: "tech.beshu.ror.audit.instances.QueryAuditLogSerializer"
                |
                |  access_control_rules:
                |
                |  - name: test_block
                |    type: allow
                |    auth_key: admin:container
                |
              """.stripMargin)

            assertLogBasedAuditSinkSettingsPresent[QueryAuditLogSerializer](
              config,
              expectedLoggerName = "readonlyrest_audit"
            )
          }
          "deprecated custom serializer is set" in {
            val config = rorConfigFromUnsafe(
              """
                |readonlyrest:
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: log
                |      serializer: "tech.beshu.ror.requestcontext.QueryAuditLogSerializer"
                |
                |  access_control_rules:
                |
                |  - name: test_block
                |    type: allow
                |    auth_key: admin:container
                |
              """.stripMargin)

            assertLogBasedAuditSinkSettingsPresent[DeprecatedAuditLogSerializerAdapter[_]](
              config,
              expectedLoggerName = "readonlyrest_audit"
            )
          }
          "all custom settings are set" in {
            val config = rorConfigFromUnsafe(
              """
                |readonlyrest:
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: log
                |      logger_name: custom_logger
                |      serializer: "tech.beshu.ror.audit.instances.QueryAuditLogSerializer"
                |
                |  access_control_rules:
                |
                |  - name: test_block
                |    type: allow
                |    auth_key: admin:container
                |
              """.stripMargin)

            assertLogBasedAuditSinkSettingsPresent[QueryAuditLogSerializer](
              config,
              expectedLoggerName = "custom_logger"
            )
          }
          "configurable serializer is set" in {
            val config = rorConfigFromUnsafe(
              """
                |readonlyrest:
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: log
                |      serializer:
                |        type: configurable
                |        verbosity_level_serialization_mode: [INFO]
                |        fields:
                |          custom_section:
                |            nested_text: "nt"
                |            nested_number: 123
                |            nested_boolean: true
                |            double_nested:
                |              double_nested_next: "dnt"
                |              triple_nested:
                |                triple_nested_next: "tnt"
                |          node_name_with_static_suffix: "{ES_NODE_NAME} with suffix"
                |          another_field: "{ES_CLUSTER_NAME} {HTTP_METHOD}"
                |          tid: "{TASK_ID}"
                |          bytes: "{CONTENT_LENGTH_IN_BYTES}"

                |  access_control_rules:
                |
                |  - name: test_block
                |    type: allow
                |    auth_key: admin:container
                |
              """.stripMargin)

            assertLogBasedAuditSinkSettingsPresent[ConfigurableAuditLogSerializer](
              config,
              expectedLoggerName = "readonlyrest_audit"
            )

            val configuredSerializer = serializer(config).asInstanceOf[ConfigurableAuditLogSerializer]

            configuredSerializer.allowedEventMode shouldBe AllowedEventMode.Include(Set(Verbosity.Info))
            configuredSerializer.fields shouldBe AuditFieldUtils.fields(
              AuditFieldUtils.withPrefix("custom_section")(
                AuditFieldPath("nested_text") -> AuditFieldValueDescriptor.StaticText("nt"),
                AuditFieldPath("nested_number") -> AuditFieldValueDescriptor.NumericValue(123),
                AuditFieldPath("nested_boolean") -> AuditFieldValueDescriptor.BooleanValue(true),
                AuditFieldUtils.withPrefix("double_nested")(
                  AuditFieldPath("double_nested_next") -> AuditFieldValueDescriptor.StaticText("dnt"),
                  AuditFieldUtils.withPrefix("triple_nested")(
                    Map(
                      AuditFieldPath("triple_nested_next") -> AuditFieldValueDescriptor.StaticText("tnt"),
                    )
                  ),
                ),
              ),
              AuditFieldPath("node_name_with_static_suffix") -> AuditFieldValueDescriptor.Combined(List(AuditFieldValueDescriptor.EsNodeName, AuditFieldValueDescriptor.StaticText(" with suffix"))),
              AuditFieldPath("another_field") -> AuditFieldValueDescriptor.Combined(List(AuditFieldValueDescriptor.EsClusterName, AuditFieldValueDescriptor.StaticText(" "), AuditFieldValueDescriptor.HttpMethod)),
              AuditFieldPath("tid") -> AuditFieldValueDescriptor.TaskId,
              AuditFieldPath("bytes") -> AuditFieldValueDescriptor.ContentLengthInBytes,
            )
          }
        }
        "'index' output type defined" when {
          "only type is set" in {
            val config = rorConfigFromUnsafe(
              """
                |readonlyrest:
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: index
                |
                |  access_control_rules:
                |
                |  - name: test_block
                |    type: allow
                |    auth_key: admin:container
                |
              """.stripMargin)

            assertIndexBasedAuditSinkSettingsPresent[BlockVerbosityAwareAuditLogSerializer](
              config,
              expectedIndexName = "readonlyrest_audit-2018-12-31",
              expectedAuditCluster = LocalAuditCluster
            )
          }
          "the output's enabled flag is set" when {
            "set to true" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: index
                  |      enabled: true
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
                """.stripMargin)

              assertIndexBasedAuditSinkSettingsPresent[BlockVerbosityAwareAuditLogSerializer](
                config,
                expectedIndexName = "readonlyrest_audit-2018-12-31",
                expectedAuditCluster = LocalAuditCluster
              )
            }
            "set to false" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: index
                  |      enabled: false
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
                """.stripMargin)

              assertSettings(
                config,
                expectedConfigs = NonEmptyList.of(AuditSink.Disabled)
              )
            }
          }
          "custom audit index name is set" in {
            val config = rorConfigFromUnsafe(
              """
                |readonlyrest:
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: index
                |      index_template: "'custom_template_'yyyyMMdd"
                |
                |  access_control_rules:
                |
                |  - name: test_block
                |    type: allow
                |    auth_key: admin:container
                |
              """.stripMargin)

            assertIndexBasedAuditSinkSettingsPresent[BlockVerbosityAwareAuditLogSerializer](
              config,
              expectedIndexName = "custom_template_20181231",
              expectedAuditCluster = LocalAuditCluster
            )
          }
          "QueryAuditLogSerializer serializer is set" in {
            val config = rorConfigFromUnsafe(
              """
                |readonlyrest:
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: index
                |      serializer: "tech.beshu.ror.audit.instances.QueryAuditLogSerializer"
                |
                |  access_control_rules:
                |
                |  - name: test_block
                |    type: allow
                |    auth_key: admin:container
                |
              """.stripMargin)

            assertIndexBasedAuditSinkSettingsPresent[QueryAuditLogSerializer](
              config,
              expectedIndexName = "readonlyrest_audit-2018-12-31",
              expectedAuditCluster = LocalAuditCluster
            )
          }
          "QueryAuditLogSerializer serializer is set and correctly serializes event without logged user" in {
            val config = rorConfigFromUnsafe(
              """
                |readonlyrest:
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: index
                |      serializer: "tech.beshu.ror.audit.instances.QueryAuditLogSerializer"
                |
                |  access_control_rules:
                |
                |  - name: test_block
                |    type: allow
                |    auth_key: admin:container
                |
              """.stripMargin)

            assertIndexBasedAuditSinkSettingsPresent[QueryAuditLogSerializer](
              config,
              expectedIndexName = "readonlyrest_audit-2018-12-31",
              expectedAuditCluster = LocalAuditCluster
            )
            val createdSerializer = serializer(config)
            val serializedResponse = createdSerializer.onResponse(
              AuditResponseContext.Forbidden(new DummyAuditRequestContext(loggedInUserName = None, attemptedUserName = None))
            )

            serializedResponse shouldBe defined
            serializedResponse.get.get("user") shouldBe "Bearer 123"
            serializedResponse.get.isNull("presented_identity")
            serializedResponse.get.isNull("logged_user")
          }
          "QueryAuditLogSerializer serializer is set and correctly serializes event with logged user" in {
            val config = rorConfigFromUnsafe(
              """
                |readonlyrest:
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: index
                |      serializer: "tech.beshu.ror.audit.instances.QueryAuditLogSerializer"
                |
                |  access_control_rules:
                |
                |  - name: test_block
                |    type: allow
                |    auth_key: admin:container
                |
              """.stripMargin)

            assertIndexBasedAuditSinkSettingsPresent[QueryAuditLogSerializer](
              config,
              expectedIndexName = "readonlyrest_audit-2018-12-31",
              expectedAuditCluster = LocalAuditCluster
            )
            val createdSerializer = serializer(config)
            val serializedResponse = createdSerializer.onResponse(
              AuditResponseContext.Forbidden(new DummyAuditRequestContext(loggedInUserName = Some("my_user")))
            )

            serializedResponse shouldBe defined
            serializedResponse.get.get("user") shouldBe "my_user"
            serializedResponse.get.get("presented_identity") shouldBe "basic auth user"
            serializedResponse.get.get("logged_user") shouldBe "my_user"
          }
          "custom environment-aware serializer is set and correctly serializes events" in {
            val config = rorConfigFromUnsafe(
              """
                |readonlyrest:
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: data_stream
                |      serializer: "tech.beshu.ror.unit.acl.factory.TestEnvironmentAwareAuditLogSerializer"
                |
                |  access_control_rules:
                |
                |  - name: test_block
                |    type: allow
                |    auth_key: admin:container
                |
              """.stripMargin)

            assertDataStreamAuditSinkSettingsPresent[EnvironmentAwareAuditLogSerializerAdapter](
              config,
              expectedDataStreamName = "readonlyrest_audit",
              expectedAuditCluster = LocalAuditCluster
            )
            val createdSerializer = serializer(config)
            val serializedResponse = createdSerializer.onResponse(AuditResponseContext.Forbidden(new DummyAuditRequestContext))

            serializedResponse shouldBe defined
            serializedResponse.get.get("custom_field_for_es_node_name") shouldBe "testEsNode"
            serializedResponse.get.get("custom_field_for_es_cluster_name") shouldBe "testEsCluster"
          }
          "ECS serializer is set" in {
            val config = rorConfigFromUnsafe(
              """
                |readonlyrest:
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: index
                |      serializer:
                |        type: ecs
                |        verbosity_level_serialization_mode: [INFO]
                |
                |  access_control_rules:
                |
                |  - name: test_block
                |    type: allow
                |    auth_key: admin:container
                |
                 """.stripMargin)

            assertIndexBasedAuditSinkSettingsPresent[EcsV1AuditLogSerializer](
              config,
              expectedIndexName = "readonlyrest_audit-2018-12-31",
              expectedAuditCluster = LocalAuditCluster
            )
            val createdSerializer = serializer(config)
            val serializedResponse = createdSerializer.onResponse(AuditResponseContext.Forbidden(new DummyAuditRequestContext))

            val expectedJsonStr =
              """{
                |  "trace" : {
                |    "id" : "corr_id_123"
                |  },
                |  "@timestamp" : "IGNORED",
                |  "ecs" : {
                |    "version" : "1.6.0"
                |  },
                |  "destination" : {
                |    "address" : "192.168.0.124"
                |  },
                |  "http" : {
                |    "request" : {
                |      "method" : "GET",
                |      "body" : {
                |        "bytes" : 123,
                |        "content" : "Full content of the request"
                |      }
                |    }
                |  },
                |  "source" : {
                |    "address" : "192.168.0.123"
                |  },
                |  "event" : {
                |    "duration" : 5000000000,
                |    "reason" : "RRTestConfigRequest",
                |    "action" : "cluster:internal_ror/user_metadata/get",
                |    "id" : "trace_id_123",
                |    "outcome" : "failure"
                |  },
                |  "error" : {},
                |  "user" : {
                |    "effective" : {
                |      "name" : "impersonated_by_user"
                |    },
                |    "name" : "logged_user"
                |  },
                |  "url" : {
                |    "path" : "/path/to/resource"
                |  },
                |  "labels" : {
                |    "es_cluster_name" : "testEsCluster",
                |    "es_task_id" : 123,
                |    "es_node_name" : "testEsNode",
                |    "ror_acl_history" : "historyEntry1, historyEntry2",
                |    "ror_detailed_reason" : "default",
                |    "ror_involved_indices" : [],
                |    "ror_final_state" : "FORBIDDEN"
                |  }
                |}""".stripMargin
            val actualJson = serializedResponse.flatMap(circeJsonWithIgnoredTimestamp)
            val expectedJson = circeJsonWithIgnoredTimestamp(new JSONObject(expectedJsonStr))
            actualJson should be(expectedJson)
          }
          "deprecated custom serializer is set" in {
            val config = rorConfigFromUnsafe(
              """
                |readonlyrest:
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: index
                |      serializer: "tech.beshu.ror.requestcontext.QueryAuditLogSerializer"
                |
                |  access_control_rules:
                |
                |  - name: test_block
                |    type: allow
                |    auth_key: admin:container
                |
              """.stripMargin)

            assertIndexBasedAuditSinkSettingsPresent[DeprecatedAuditLogSerializerAdapter[_]](
              config,
              expectedIndexName = "readonlyrest_audit-2018-12-31",
              expectedAuditCluster = LocalAuditCluster
            )
          }
          "custom audit cluster is set" in {
            val config = rorConfigFromUnsafe(
              """
                |readonlyrest:
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: index
                |      cluster: ["1.1.1.1"]
                |
                |  access_control_rules:
                |
                |  - name: test_block
                |    type: allow
                |    auth_key: admin:container
                |
              """.stripMargin)

            assertIndexBasedAuditSinkSettingsPresent[BlockVerbosityAwareAuditLogSerializer](
              config,
              expectedIndexName = "readonlyrest_audit-2018-12-31",
              expectedAuditCluster = RemoteAuditCluster(
                UniqueNonEmptyList.of(AuditClusterNode(Uri.parse("1.1.1.1"))),
                ClusterMode.RoundRobin,
                credentials = None
              )
            )
          }
          "all audit settings are custom" in {
            val config = rorConfigFromUnsafe(
              """
                |readonlyrest:
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: index
                |      index_template: "'custom_template_'yyyyMMdd"
                |      serializer: "tech.beshu.ror.audit.instances.QueryAuditLogSerializer"
                |      cluster: ["1.1.1.1"]
                |
                |  access_control_rules:
                |
                |  - name: test_block
                |    type: allow
                |    auth_key: admin:container
                |
              """.stripMargin)

            assertIndexBasedAuditSinkSettingsPresent[QueryAuditLogSerializer](
              config,
              expectedIndexName = "custom_template_20181231",
              expectedAuditCluster = RemoteAuditCluster(
                UniqueNonEmptyList.of(AuditClusterNode(Uri.parse("1.1.1.1"))),
                ClusterMode.RoundRobin,
                credentials = None
              )
            )
          }
        }
        "'data_stream' output type defined" when {
          "only type is set" in {
            val config = rorConfigFromUnsafe(
              """
                |readonlyrest:
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: data_stream
                |
                |  access_control_rules:
                |
                |  - name: test_block
                |    type: allow
                |    auth_key: admin:container
                |
              """.stripMargin)

            assertDataStreamAuditSinkSettingsPresent[BlockVerbosityAwareAuditLogSerializer](
              config,
              expectedDataStreamName = "readonlyrest_audit",
              expectedAuditCluster = LocalAuditCluster
            )
          }
          "the output's enabled flag is set" when {
            "set to true" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: data_stream
                  |      enabled: true
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
                """.stripMargin)

              assertDataStreamAuditSinkSettingsPresent[BlockVerbosityAwareAuditLogSerializer](
                config,
                expectedDataStreamName = "readonlyrest_audit",
                expectedAuditCluster = LocalAuditCluster
              )
            }
            "set to false" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: data_stream
                  |      enabled: false
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
                """.stripMargin)

              assertSettings(
                config,
                expectedConfigs = NonEmptyList.of(AuditSink.Disabled)
              )
            }
          }
          "custom audit data stream name is set" in {
            val config = rorConfigFromUnsafe(
              """
                |readonlyrest:
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: data_stream
                |      data_stream: "custom_audit_data_stream"
                |
                |  access_control_rules:
                |
                |  - name: test_block
                |    type: allow
                |    auth_key: admin:container
                |
              """.stripMargin)

            assertDataStreamAuditSinkSettingsPresent[BlockVerbosityAwareAuditLogSerializer](
              config,
              expectedDataStreamName = "custom_audit_data_stream",
              expectedAuditCluster = LocalAuditCluster
            )
          }
          "custom serializer is set" in {
            val config = rorConfigFromUnsafe(
              """
                |readonlyrest:
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: data_stream
                |      serializer: "tech.beshu.ror.audit.instances.QueryAuditLogSerializer"
                |
                |  access_control_rules:
                |
                |  - name: test_block
                |    type: allow
                |    auth_key: admin:container
                |
              """.stripMargin)

            assertDataStreamAuditSinkSettingsPresent[QueryAuditLogSerializer](
              config,
              expectedDataStreamName = "readonlyrest_audit",
              expectedAuditCluster = LocalAuditCluster
            )
          }
          "deprecated custom serializer is set" in {
            val config = rorConfigFromUnsafe(
              """
                |readonlyrest:
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: data_stream
                |      serializer: "tech.beshu.ror.requestcontext.QueryAuditLogSerializer"
                |
                |  access_control_rules:
                |
                |  - name: test_block
                |    type: allow
                |    auth_key: admin:container
                |
              """.stripMargin)

            assertDataStreamAuditSinkSettingsPresent[DeprecatedAuditLogSerializerAdapter[_]](
              config,
              expectedDataStreamName = "readonlyrest_audit",
              expectedAuditCluster = LocalAuditCluster
            )
          }
          "custom audit cluster is set" when {
            "array syntax for cluster" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: data_stream
                  |      cluster: ["1.1.1.1"]
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
                """.stripMargin)
              assertDataStreamAuditSinkSettingsPresent[BlockVerbosityAwareAuditLogSerializer](
                config,
                expectedDataStreamName = "readonlyrest_audit",
                expectedAuditCluster = RemoteAuditCluster(
                  UniqueNonEmptyList.of(AuditClusterNode(Uri.parse("1.1.1.1"))),
                  ClusterMode.RoundRobin,
                  credentials = None
                )
              )
            }
            "array syntax for cluster with credentials" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: data_stream
                  |      cluster: [ "https://user:pass@1.1.1.1:9200" ]
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
                """.stripMargin)
              assertDataStreamAuditSinkSettingsPresent[BlockVerbosityAwareAuditLogSerializer](
                config,
                expectedDataStreamName = "readonlyrest_audit",
                expectedAuditCluster = RemoteAuditCluster(
                  UniqueNonEmptyList.of(AuditClusterNode(Uri.parse("https://user:pass@1.1.1.1:9200"))),
                  ClusterMode.RoundRobin,
                  Some(NodeCredentials("user", "pass"))
                )
              )
            }
            "extended syntax for cluster" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: data_stream
                  |      cluster:
                  |        nodes: ["1.1.1.1"]
                  |        mode: round-robin
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
                """.stripMargin)
              assertDataStreamAuditSinkSettingsPresent[BlockVerbosityAwareAuditLogSerializer](
                config,
                expectedDataStreamName = "readonlyrest_audit",
                expectedAuditCluster = RemoteAuditCluster(
                  UniqueNonEmptyList.of(AuditClusterNode(Uri.parse("1.1.1.1"))),
                  ClusterMode.RoundRobin,
                  credentials = None
                )
              )
            }
            "extended syntax for cluster with credentials" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: data_stream
                  |      cluster:
                  |        nodes: ["1.1.1.1", "2.2.2.2", "3.3.3.3"]
                  |        mode: round-robin
                  |        username: "user"
                  |        password: "pass"
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
                """.stripMargin)
              assertDataStreamAuditSinkSettingsPresent[BlockVerbosityAwareAuditLogSerializer](
                config,
                expectedDataStreamName = "readonlyrest_audit",
                expectedAuditCluster = RemoteAuditCluster(
                  UniqueNonEmptyList.of(
                    AuditClusterNode(Uri.parse("1.1.1.1")),
                    AuditClusterNode(Uri.parse("2.2.2.2")),
                    AuditClusterNode(Uri.parse("3.3.3.3"))
                  ),
                  ClusterMode.RoundRobin,
                  Some(NodeCredentials("user", "pass"))
                )
              )
            }
          }
          "all audit settings are custom" in {
            val config = rorConfigFromUnsafe(
              """
                |readonlyrest:
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: data_stream
                |      data_stream: "custom_audit_data_stream"
                |      serializer: "tech.beshu.ror.audit.instances.QueryAuditLogSerializer"
                |      cluster:
                |        nodes: ["1.1.1.1", "2.2.2.2"]
                |        mode: round-robin
                |        username: "user"
                |        password: "pass"
                |
                |  access_control_rules:
                |
                |  - name: test_block
                |    type: allow
                |    auth_key: admin:container
                |
              """.stripMargin)

            assertDataStreamAuditSinkSettingsPresent[QueryAuditLogSerializer](
              config,
              expectedDataStreamName = "custom_audit_data_stream",
              expectedAuditCluster = RemoteAuditCluster(
                UniqueNonEmptyList.of(
                  AuditClusterNode(Uri.parse("1.1.1.1")),
                  AuditClusterNode(Uri.parse("2.2.2.2")),
                ),
                ClusterMode.RoundRobin,
                Some(NodeCredentials("user", "pass"))
              )
            )
          }
          "ES version is greater than or equal 7.9.0" in {
            val esVersions =
              List(
                EsVersion(8, 17, 0),
                EsVersion(8, 1, 0),
                EsVersion(8, 0, 0),
                EsVersion(7, 17, 27),
                EsVersion(7, 10, 0),
                EsVersion(7, 9, 1),
                EsVersion(7, 9, 0),
              )

            val config = rorConfigFromUnsafe(
              """
                |readonlyrest:
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: data_stream
                |      data_stream: "custom_audit_data_stream"
                |      serializer: "tech.beshu.ror.audit.instances.QueryAuditLogSerializer"
                |      cluster: ["1.1.1.1"]
                |
                |  access_control_rules:
                |
                |  - name: test_block
                |    type: allow
                |    auth_key: admin:container
                |
              """.stripMargin)

            esVersions.foreach { esVersion =>
              assertDataStreamAuditSinkSettingsPresent[QueryAuditLogSerializer](
                config,
                expectedDataStreamName = "custom_audit_data_stream",
                expectedAuditCluster = RemoteAuditCluster(
                  UniqueNonEmptyList.of(AuditClusterNode(Uri.parse("1.1.1.1"))),
                  ClusterMode.RoundRobin,
                  credentials = None
                ),
                esVersion = esVersion
              )
            }
          }
        }

        "all output types defined" in {
          val config = rorConfigFromUnsafe(
            """
              |readonlyrest:
              |  audit:
              |    enabled: true
              |    outputs:
              |    - type: index
              |    - type: log
              |      serializer: "tech.beshu.ror.audit.instances.QueryAuditLogSerializer"
              |    - type: data_stream
              |
              |  access_control_rules:
              |
              |  - name: test_block
              |    type: allow
              |    auth_key: admin:container
              |
            """.stripMargin)

          val core = factory()
            .createCoreFrom(
              config,
              RorConfigurationIndex(IndexName.Full(".readonlyrest")),
              MockHttpClientsFactory,
              MockLdapConnectionPoolProvider,
              NoOpMocksProvider
            )
            .runSyncUnsafe()
          inside(core) { case Right(Core(_, RorConfig(_, _, _, Some(auditingSettings)))) =>
            auditingSettings.auditSinks.size should be(3)

            val sink1 = auditingSettings.auditSinks.head
            sink1 shouldBe a[AuditSink.Enabled]
            val enabledSink1 = sink1.asInstanceOf[AuditSink.Enabled].config
            enabledSink1 shouldBe a[Config.EsIndexBasedSink]
            val sink1Config = enabledSink1.asInstanceOf[Config.EsIndexBasedSink]
            sink1Config.rorAuditIndexTemplate.indexName(zonedDateTime.toInstant) should be(indexName("readonlyrest_audit-2018-12-31"))
            sink1Config.logSerializer shouldBe a[BlockVerbosityAwareAuditLogSerializer]
            sink1Config.auditCluster shouldBe AuditCluster.LocalAuditCluster

            val sink2 = auditingSettings.auditSinks.toList(1)
            sink2 shouldBe a[AuditSink.Enabled]
            val enabledSink2 = sink2.asInstanceOf[AuditSink.Enabled].config
            enabledSink2 shouldBe a[Config.LogBasedSink]
            val sink2Config = enabledSink2.asInstanceOf[Config.LogBasedSink]
            sink2Config.loggerName should be(RorAuditLoggerName("readonlyrest_audit"))
            sink2Config.logSerializer shouldBe a[QueryAuditLogSerializer]

            val sink3 = auditingSettings.auditSinks.toList(2)
            sink3 shouldBe a[AuditSink.Enabled]
            val enabledSink3 = sink3.asInstanceOf[AuditSink.Enabled].config
            enabledSink3 shouldBe a[Config.EsDataStreamBasedSink]
            val sink3Config = enabledSink3.asInstanceOf[Config.EsDataStreamBasedSink]
            sink3Config.rorAuditDataStream.dataStream should be(fullDataStreamName("readonlyrest_audit"))
            sink3Config.logSerializer shouldBe a[BlockVerbosityAwareAuditLogSerializer]
            sink3Config.auditCluster shouldBe AuditCluster.LocalAuditCluster
          }
        }
        "one of outputs is disabled" in {
          val config = rorConfigFromUnsafe(
            """
              |readonlyrest:
              |  audit:
              |    enabled: true
              |    outputs:
              |    - type: index
              |      enabled: false
              |    - type: log
              |      serializer: "tech.beshu.ror.audit.instances.QueryAuditLogSerializer"
              |
              |  access_control_rules:
              |
              |  - name: test_block
              |    type: allow
              |    auth_key: admin:container
              |
            """.stripMargin)

          val core = factory()
            .createCoreFrom(
              config,
              RorConfigurationIndex(IndexName.Full(".readonlyrest")),
              MockHttpClientsFactory,
              MockLdapConnectionPoolProvider,
              NoOpMocksProvider
            )
            .runSyncUnsafe()
          inside(core) { case Right(Core(_, RorConfig(_, _, _, Some(auditingSettings)))) =>
            auditingSettings.auditSinks.size should be(2)

            val sink1 = auditingSettings.auditSinks.head
            sink1 should be(AuditSink.Disabled)

            val sink2 = auditingSettings.auditSinks.toList(1)
            sink2 shouldBe a[AuditSink.Enabled]
            val enabledSink2 = sink2.asInstanceOf[AuditSink.Enabled].config
            enabledSink2 shouldBe a[Config.LogBasedSink]
            val sink2Config = enabledSink2.asInstanceOf[Config.LogBasedSink]
            sink2Config.loggerName should be(RorAuditLoggerName("readonlyrest_audit"))
            sink2Config.logSerializer shouldBe a[QueryAuditLogSerializer]
          }
        }
        "not be able to be loaded from config" when {
          "'log' output type" when {
            "not supported custom serializer is set" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: log
                  |      serializer: "tech.beshu.ror.accesscontrol.blocks.RuleOrdering"
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
              """.stripMargin)

              assertInvalidSettings(
                config,
                expectedErrorMessage = "Class tech.beshu.ror.accesscontrol.blocks.RuleOrdering is not a subclass of tech.beshu.ror.audit.AuditLogSerializer or tech.beshu.ror.requestcontext.AuditLogSerializer"
              )
            }
            "logger name is empty" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: log
                  |      logger_name: ""
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
              """.stripMargin)

              assertInvalidSettings(
                config,
                expectedErrorMessage = "The audit 'logger_name' cannot be empty"
              )
            }
          }
          "'index' output type" when {
            "not supported custom serializer is set" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: index
                  |      serializer: "tech.beshu.ror.accesscontrol.blocks.RuleOrdering"
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
              """.stripMargin)

              assertInvalidSettings(
                config,
                expectedErrorMessage = "Class tech.beshu.ror.accesscontrol.blocks.RuleOrdering is not a subclass of tech.beshu.ror.audit.AuditLogSerializer or tech.beshu.ror.requestcontext.AuditLogSerializer"
              )
            }
            "custom audit index name pattern is invalid" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: index
                  |      index_template: "invalid pattern"
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
              """.stripMargin)

              assertInvalidSettings(
                config,
                expectedErrorMessage = "Illegal pattern specified for audit_index_template. Have you misplaced quotes? Search for 'DateTimeFormatter patterns' to learn the syntax. Pattern was: invalid pattern error: Unknown pattern letter: i"
              )
            }
            "remote cluster is empty list (array syntax)" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: index
                  |      cluster: []
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
              """.stripMargin)

              assertInvalidSettings(
                config,
                expectedErrorMessage = "Non empty list of valid URI is required"
              )
            }
            "remote cluster has nodes with inconsistent credentials" in {
              val tests = List(
                List("http://user1:password@1.1.1.1", "http://user2:password@1.1.1.1"),
                List("http://user1:password1@1.1.1.1", "http://user1:password2@1.1.1.1"),
                List("http://1.1.1.1", "http://user2:password@1.1.1.1"),
                List("http://user1:password@1.1.1.1", "http://1.1.1.1"),
              )

              tests.foreach { auditNodes =>
                val config = rorConfigFromUnsafe(
                  s"""
                     |readonlyrest:
                     |  audit:
                     |    enabled: true
                     |    outputs:
                     |    - type: index
                     |      cluster:
                     |        nodes: ${auditNodes.map(n => s"\"$n\"").mkString("[", ",", "]")}
                     |        mode: round-robin
                     |
                     |  access_control_rules:
                     |
                     |  - name: test_block
                     |    type: allow
                     |    auth_key: admin:container
                  """.stripMargin)

                assertInvalidSettings(
                  config,
                  expectedErrorMessage = s"One or more audit cluster nodes have inconsistent credentials: ${auditNodes.mkString(", ")}"
                )
              }
            }

            "remote cluster is empty list (extended syntax)" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: index
                  |      cluster:
                  |        nodes: []
                  |        mode: round-robin
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
              """.stripMargin)

              assertInvalidSettings(
                config,
                expectedErrorMessage = "Error for field 'nodes': Non empty list of valid URI is required"
              )
            }
            "remote cluster has invalid mode" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: index
                  |      cluster:
                  |        nodes: ["1.1.1.1"]
                  |        mode: not-existing-mode
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                """.stripMargin)

              assertInvalidSettings(
                config,
                expectedErrorMessage = "Error for field 'mode': Unknown cluster mode [not-existing-mode], allowed values are: [round-robin]"
              )
            }
          }
          "'data_stream' output type" when {
            "not supported custom serializer is set" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: data_stream
                  |      serializer: "tech.beshu.ror.accesscontrol.blocks.RuleOrdering"
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
              """.stripMargin)

              assertInvalidSettings(
                config,
                expectedErrorMessage = "Class tech.beshu.ror.accesscontrol.blocks.RuleOrdering is not a subclass of tech.beshu.ror.audit.AuditLogSerializer or tech.beshu.ror.requestcontext.AuditLogSerializer"
              )
            }
            "data stream name is invalid" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: data_stream
                  |      data_stream: ".ds-INVALID-data-stream-name#"
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
              """.stripMargin)

              assertInvalidSettings(
                config,
                expectedErrorMessage = "Error for field 'data_stream': Illegal format for ROR audit 'data_stream' name - Data stream '.ds-INVALID-data-stream-name#' has an invalid format. Cause: " +
                  "name must be lowercase, " +
                  "name must not contain forbidden characters '\\', '/', '*', '?', '\"', '<', '>', '|', ',', '#', ':', ' ', " +
                  "name must not start with '-', '_', '+', '.ds-'."

              )
            }
            "remote cluster is empty list" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: data_stream
                  |      cluster: []
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
              """.stripMargin)

              assertInvalidSettings(
                config,
                expectedErrorMessage = "Error for field 'cluster': Non empty list of valid URI is required"
              )
            }
            "es version is lower than 7.9.0" in {
              val esVersions =
                List(
                  EsVersion(7, 8, 2),
                  EsVersion(7, 8, 1),
                  EsVersion(7, 8, 0),
                  EsVersion(7, 7, 0),
                  EsVersion(7, 0, 0),
                  EsVersion(6, 8, 23),
                  EsVersion(5, 0, 5),
                )

              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: data_stream
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
                """.stripMargin)

              esVersions.foreach { esVersion =>
                assertInvalidSettings(
                  config,
                  expectedErrorMessage = s"Error for field 'type': Data stream audit output is supported from Elasticsearch version 7.9.0, " +
                    s"but your version is ${esVersion.major}.${esVersion.minor}.${esVersion.revision}. Use 'index' type or upgrade to 7.9.0 or later.",
                  esVersion = esVersion
                )
              }
            }
          }
          "unknown output type is set" in {
            val config = rorConfigFromUnsafe(
              """
                |readonlyrest:
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: custom_type
                |
                |  access_control_rules:
                |
                |  - name: test_block
                |    type: allow
                |    auth_key: admin:container
                |
            """.stripMargin)

            assertInvalidSettings(
              config,
              expectedErrorMessage = "Error for field 'type': Unsupported type of audit output: custom_type. Supported types: [data_stream, index, log]"
            )
          }
          "unknown output type is set when using simple format" in {
            val config = rorConfigFromUnsafe(
              """
                |readonlyrest:
                |  audit:
                |    enabled: true
                |    outputs: [ custom_type ]
                |
                |  access_control_rules:
                |
                |  - name: test_block
                |    type: allow
                |    auth_key: admin:container
                |
              """.stripMargin)

            assertInvalidSettings(
              config,
              expectedErrorMessage = "Unsupported type of audit output: custom_type. Supported types: [data_stream, index, log]"
            )

            assertInvalidSettings(
              config,
              expectedErrorMessage = "Unsupported type of audit output: custom_type. Supported types: [index, log]",
              esVersion = EsVersion(7, 8, 0)
            )
          }
          "'outputs' array is empty" in {
            val config = rorConfigFromUnsafe(
              """
                |readonlyrest:
                |  audit:
                |    enabled: true
                |    outputs: []
                |
                |  access_control_rules:
                |
                |  - name: test_block
                |    type: allow
                |    auth_key: admin:container
                |
            """.stripMargin)

            assertInvalidSettings(
              config,
              expectedErrorMessage = "The audit 'outputs' array cannot be empty"
            )
          }
          "configurable serializer is set with invalid value descriptor" in {
            val config = rorConfigFromUnsafe(
              """
                |readonlyrest:
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: log
                |      serializer:
                |        type: configurable
                |        verbosity_level_serialization_mode: [INFO]
                |        fields:
                |          node_name_with_static_suffix: "{ES_NODE_NAME} with suffix"
                |          another_field: "{ES_CLUSTER_NAME} {HTTP_METHOD2}"
                |          tid: "{TASK_ID}"
                |          bytes: "{CONTENT_LENGTH_IN_BYTES}"
                |  access_control_rules:
                |
                |  - name: test_block
                |    type: allow
                |    auth_key: admin:container
                |
              """.stripMargin)

            assertInvalidSettings(
              config,
              expectedErrorMessage = "Configurable serializer is used, but the 'fields' setting is missing or invalid: There are invalid placeholder values: HTTP_METHOD2"
            )
          }
          "configurable serializer is set, but without fields setting" in {
            val config = rorConfigFromUnsafe(
              """
                |readonlyrest:
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: log
                |      serializer:
                |        type: configurable
                |        verbosity_level_serialization_mode: [INFO]
                |  access_control_rules:
                |
                |  - name: test_block
                |    type: allow
                |    auth_key: admin:container
                |
              """.stripMargin)

            assertInvalidSettings(
              config,
              expectedErrorMessage = "Configurable serializer is used, but the 'fields' setting is missing or invalid: Missing required field"
            )
          }
        }
      }
      "deprecated format is used" should {
        "ignore deprecated fields when both formats are used at once" in {
          val config = rorConfigFromUnsafe(
            """
              |readonlyrest:
              |  audit:
              |    enabled: true
              |    outputs:
              |    - type: index
              |    # deprecated fields
              |    collector: false
              |    serializer: "tech.beshu.ror.audit.instances.QueryAuditLogSerializer"
              |
              |  access_control_rules:
              |
              |  - name: test_block
              |    type: allow
              |    auth_key: admin:container
              |
          """.stripMargin)

          assertIndexBasedAuditSinkSettingsPresent[BlockVerbosityAwareAuditLogSerializer](
            config,
            expectedIndexName = "readonlyrest_audit-2018-12-31",
            expectedAuditCluster = LocalAuditCluster
          )
        }
        "be optional" when {
          "audit collector is disabled" when {
            "'audit' section is defined" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit:
                  |    collector: false
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
              """.stripMargin)

              assertSettingsNoPresent(config)
            }
            "'audit' section is not defined" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit_collector: false
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
            """.stripMargin)

              assertSettingsNoPresent(config)
            }
          }
        }
        "be able to be loaded from config" when {
          "'audit' section is defined" when {
            "audit collector is enabled" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit:
                  |    collector: true
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
              """.stripMargin)

              assertIndexBasedAuditSinkSettingsPresent[BlockVerbosityAwareAuditLogSerializer](
                config,
                expectedIndexName = "readonlyrest_audit-2018-12-31",
                expectedAuditCluster = LocalAuditCluster
              )
            }
            "custom audit index name is set" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit:
                  |    collector: true
                  |    index_template: "'custom_template_'yyyyMMdd"
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
              """.stripMargin)

              assertIndexBasedAuditSinkSettingsPresent[BlockVerbosityAwareAuditLogSerializer](
                config,
                expectedIndexName = "custom_template_20181231",
                expectedAuditCluster = LocalAuditCluster
              )
            }
            "custom serializer is set" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit:
                  |    collector: true
                  |    serializer: "tech.beshu.ror.audit.instances.QueryAuditLogSerializer"
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
              """.stripMargin)

              assertIndexBasedAuditSinkSettingsPresent[QueryAuditLogSerializer](
                config,
                expectedIndexName = "readonlyrest_audit-2018-12-31",
                expectedAuditCluster = LocalAuditCluster
              )
            }
            "deprecated custom serializer is set" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit:
                  |    collector: true
                  |    serializer: "tech.beshu.ror.requestcontext.QueryAuditLogSerializer"
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
              """.stripMargin)

              assertIndexBasedAuditSinkSettingsPresent[DeprecatedAuditLogSerializerAdapter[_]](
                config,
                expectedIndexName = "readonlyrest_audit-2018-12-31",
                expectedAuditCluster = LocalAuditCluster
              )
            }
            "custom audit cluster is set" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit:
                  |    collector: true
                  |    cluster: ["1.1.1.1"]
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
              """.stripMargin)

              assertIndexBasedAuditSinkSettingsPresent[BlockVerbosityAwareAuditLogSerializer](
                config,
                expectedIndexName = "readonlyrest_audit-2018-12-31",
                expectedAuditCluster = RemoteAuditCluster(
                  UniqueNonEmptyList.of(AuditClusterNode(Uri.parse("1.1.1.1"))),
                  ClusterMode.RoundRobin,
                  credentials = None
                )
              )
            }
            "all audit settings are custom" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit:
                  |    collector: true
                  |    index_template: "'custom_template_'yyyyMMdd"
                  |    serializer: "tech.beshu.ror.audit.instances.QueryAuditLogSerializer"
                  |    cluster: ["1.1.1.1"]
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
              """.stripMargin)

              assertIndexBasedAuditSinkSettingsPresent[QueryAuditLogSerializer](
                config,
                expectedIndexName = "custom_template_20181231",
                expectedAuditCluster = RemoteAuditCluster(
                  UniqueNonEmptyList.of(AuditClusterNode(Uri.parse("1.1.1.1"))),
                  ClusterMode.RoundRobin,
                  credentials = None
                )
              )
            }
          }
          "'audit' section is not defined" when {
            "audit collector is enabled" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit_collector: true
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
              """.stripMargin)

              assertIndexBasedAuditSinkSettingsPresent[BlockVerbosityAwareAuditLogSerializer](
                config,
                expectedIndexName = "readonlyrest_audit-2018-12-31",
                expectedAuditCluster = LocalAuditCluster
              )
            }
            "custom audit index name is set" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit_collector: true
                  |  audit_index_template: "'custom_template_'yyyyMMdd"
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
              """.stripMargin)

              assertIndexBasedAuditSinkSettingsPresent[BlockVerbosityAwareAuditLogSerializer](
                config,
                expectedIndexName = "custom_template_20181231",
                expectedAuditCluster = LocalAuditCluster
              )
            }
            "custom serializer is set" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit_collector: true
                  |  audit_serializer: "tech.beshu.ror.audit.instances.QueryAuditLogSerializer"
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
              """.stripMargin)

              assertIndexBasedAuditSinkSettingsPresent[QueryAuditLogSerializer](
                config,
                expectedIndexName = "readonlyrest_audit-2018-12-31",
                expectedAuditCluster = LocalAuditCluster
              )
            }
            "deprecated custom serializer is set" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit_collector: true
                  |  audit_serializer: "tech.beshu.ror.requestcontext.QueryAuditLogSerializer"
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
              """.stripMargin)

              assertIndexBasedAuditSinkSettingsPresent[DeprecatedAuditLogSerializerAdapter[_]](
                config,
                expectedIndexName = "readonlyrest_audit-2018-12-31",
                expectedAuditCluster = LocalAuditCluster
              )
            }
            "custom audit cluster is set" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit_collector: true
                  |  audit_cluster: ["http://user:test@1.1.1.1"]
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
              """.stripMargin)

              assertIndexBasedAuditSinkSettingsPresent[BlockVerbosityAwareAuditLogSerializer](
                config,
                expectedIndexName = "readonlyrest_audit-2018-12-31",
                expectedAuditCluster = RemoteAuditCluster(
                  UniqueNonEmptyList.of(AuditClusterNode(Uri.parse("http://user:test@1.1.1.1"))),
                  ClusterMode.RoundRobin,
                  credentials = Some(NodeCredentials("user", "test"))
                )
              )
            }
            "all audit settings are custom" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit_collector: true
                  |  audit_index_template: "'custom_template_'yyyyMMdd"
                  |  audit_serializer: "tech.beshu.ror.audit.instances.QueryAuditLogSerializer"
                  |  audit_cluster: ["1.1.1.1"]
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
              """.stripMargin)

              assertIndexBasedAuditSinkSettingsPresent[QueryAuditLogSerializer](
                config,
                expectedIndexName = "custom_template_20181231",
                expectedAuditCluster = RemoteAuditCluster(
                  UniqueNonEmptyList.of(AuditClusterNode(Uri.parse("1.1.1.1"))),
                  ClusterMode.RoundRobin,
                  None
                )
              )
            }

          }
        }
        "not be able to be loaded from config" when {
          "'audit' section is defined" when {
            "not supported custom serializer is set" in {
              val config = rorConfigWithAudit(
                """
                  |audit:
                  |  collector: true
                  |  serializer: "tech.beshu.ror.accesscontrol.blocks.RuleOrdering"
              """.stripMargin)

              assertInvalidSettings(
                config,
                expectedErrorMessage = "Class tech.beshu.ror.accesscontrol.blocks.RuleOrdering is not a subclass of tech.beshu.ror.audit.AuditLogSerializer or tech.beshu.ror.requestcontext.AuditLogSerializer"
              )
            }
            "custom audit index name pattern is invalid" in {
              val config = rorConfigWithAudit(
                """
                  |audit:
                  |  collector: true
                  |  index_template: "invalid pattern"
              """.stripMargin)

              assertInvalidSettings(
                config,
                expectedErrorMessage = "Illegal pattern specified for audit_index_template. Have you misplaced quotes? Search for 'DateTimeFormatter patterns' to learn the syntax. Pattern was: invalid pattern error: Unknown pattern letter: i"
              )
            }
            "remote cluster is empty list" in {
              val config = rorConfigWithAudit(
                """
                  |audit:
                  |  collector: true
                  |  cluster: []
                """.stripMargin)

              assertInvalidSettings(
                config,
                expectedErrorMessage = "Non empty list of valid URI is required"
              )
            }
          }
          "'audit' section is not defined" when {
            "not supported custom serializer is set" in {
              val config = rorConfigWithAudit(
                """
                  |audit_collector: true
                  |audit_serializer: "tech.beshu.ror.accesscontrol.blocks.RuleOrdering"
              """.stripMargin)

              assertInvalidSettings(
                config,
                expectedErrorMessage = "Class tech.beshu.ror.accesscontrol.blocks.RuleOrdering is not a subclass of tech.beshu.ror.audit.AuditLogSerializer or tech.beshu.ror.requestcontext.AuditLogSerializer"
              )
            }
            "custom audit index name pattern is invalid" in {
              val config = rorConfigWithAudit(
                """
                  |audit_collector: true
                  |audit_index_template: "invalid pattern"
                """.stripMargin)

              assertInvalidSettings(
                config,
                expectedErrorMessage = "Illegal pattern specified for audit_index_template. Have you misplaced quotes? Search for 'DateTimeFormatter patterns' to learn the syntax. Pattern was: invalid pattern error: Unknown pattern letter: i"
              )
            }
            "remote cluster is empty list" in {
              val config = rorConfigWithAudit(
                """
                  |audit_collector: true
                  |audit_cluster: []
                """.stripMargin)
              assertInvalidSettings(
                config,
                expectedErrorMessage = "Non empty list of valid URI is required"
              )
            }
          }
        }
      }
    }
  }

  private def rorConfigWithAudit(auditSection: String) = {
    def indent(n: Int)(str: String): String = {
      val indent =  " " * n
      str.linesIterator.map(indent + _).mkString("\n")
    }

    val rawConfig =
      s"""
         |readonlyrest:
         |  ${indent(2)(auditSection)}
         |  access_control_rules:
         |
         |  - name: test_block
         |    type: allow
         |    auth_key: admin:container
    """.stripMargin
    println(rawConfig)
    rorConfigFromUnsafe(rawConfig)
  }

  private def assertSettingsNoPresent(config: RawRorConfig): Unit = {
    val core = factory()
      .createCoreFrom(
        config,
        RorConfigurationIndex(IndexName.Full(".readonlyrest")),
        MockHttpClientsFactory,
        MockLdapConnectionPoolProvider,
        NoOpMocksProvider
      )
      .runSyncUnsafe()
    inside(core) { case Right(Core(_, RorConfig(_, _, _, None))) => }
  }

  private def assertSettings(config: RawRorConfig, expectedConfigs: NonEmptyList[AuditSink]): Unit = {
    val core = factory()
      .createCoreFrom(
        config,
        RorConfigurationIndex(IndexName.Full(".readonlyrest")),
        MockHttpClientsFactory,
        MockLdapConnectionPoolProvider,
        NoOpMocksProvider
      )
      .runSyncUnsafe()
    inside(core) { case Right(Core(_, RorConfig(_, _, _, Some(settings)))) =>
      settings.auditSinks should be(expectedConfigs)
    }
  }


  private def assertIndexBasedAuditSinkSettingsPresent[EXPECTED_SERIALIZER: ClassTag](config: RawRorConfig,
                                                                                      expectedIndexName: NonEmptyString,
                                                                                      expectedAuditCluster: AuditCluster) = {
    val core = factory()
      .createCoreFrom(
        config,
        RorConfigurationIndex(IndexName.Full(".readonlyrest")),
        MockHttpClientsFactory,
        MockLdapConnectionPoolProvider,
        NoOpMocksProvider
      )
      .runSyncUnsafe()
    inside(core) { case Right(Core(_, RorConfig(_, _, _, Some(auditingSettings)))) =>
      auditingSettings.auditSinks.size should be(1)

      val headSink = auditingSettings.auditSinks.head
      headSink shouldBe a[AuditSink.Enabled]

      val headSinkConfig = headSink.asInstanceOf[AuditSink.Enabled].config
      headSinkConfig shouldBe a[Config.EsIndexBasedSink]

      val sinkConfig = headSinkConfig.asInstanceOf[Config.EsIndexBasedSink]
      sinkConfig.rorAuditIndexTemplate.indexName(zonedDateTime.toInstant) should be(indexName(expectedIndexName))
      sinkConfig.logSerializer shouldBe a[EXPECTED_SERIALIZER]
      sinkConfig.auditCluster shouldBe expectedAuditCluster
    }
  }

  private def assertDataStreamAuditSinkSettingsPresent[EXPECTED_SERIALIZER: ClassTag](config: RawRorConfig,
                                                                                      expectedDataStreamName: NonEmptyString,
                                                                                      expectedAuditCluster: AuditCluster,
                                                                                      esVersion: EsVersion = defaultEsVersionForTests) = {
    val core = factory(esVersion)
      .createCoreFrom(
        config,
        RorConfigurationIndex(IndexName.Full(".readonlyrest")),
        MockHttpClientsFactory,
        MockLdapConnectionPoolProvider,
        NoOpMocksProvider
      )
      .runSyncUnsafe()
    inside(core) { case Right(Core(_, RorConfig(_, _, _, Some(auditingSettings)))) =>
      auditingSettings.auditSinks.size should be(1)

      val headSink = auditingSettings.auditSinks.head
      headSink shouldBe a[AuditSink.Enabled]

      val headSinkConfig = headSink.asInstanceOf[AuditSink.Enabled].config
      headSinkConfig shouldBe a[Config.EsDataStreamBasedSink]

      val sinkConfig = headSinkConfig.asInstanceOf[Config.EsDataStreamBasedSink]
      sinkConfig.rorAuditDataStream.dataStream should be(fullDataStreamName(expectedDataStreamName))
      sinkConfig.logSerializer shouldBe a[EXPECTED_SERIALIZER]
      sinkConfig.auditCluster shouldBe expectedAuditCluster
    }
  }

  private def serializer(config: RawRorConfig,
                         esVersion: EsVersion = defaultEsVersionForTests): AuditLogSerializer = {
    val core = factory(esVersion)
      .createCoreFrom(
        config,
        RorConfigurationIndex(IndexName.Full(".readonlyrest")),
        MockHttpClientsFactory,
        MockLdapConnectionPoolProvider,
        NoOpMocksProvider
      )
      .runSyncUnsafe()

    core match {
      case Right(Core(_, RorConfig(_, _, _, Some(auditingSettings)))) =>
        val headSink = auditingSettings.auditSinks.head
        val headSinkConfig = headSink.asInstanceOf[AuditSink.Enabled].config
        headSinkConfig.logSerializer
      case _ =>
        throw new IllegalStateException("Expected auditingSettings are not present")
    }
  }

  private def assertLogBasedAuditSinkSettingsPresent[EXPECTED_SERIALIZER: ClassTag](config: RawRorConfig,
                                                                                    expectedLoggerName: NonEmptyString) = {
    val core = factory()
      .createCoreFrom(
        config,
        RorConfigurationIndex(IndexName.Full(".readonlyrest")),
        MockHttpClientsFactory,
        MockLdapConnectionPoolProvider,
        NoOpMocksProvider
      )
      .runSyncUnsafe()
    inside(core) { case Right(Core(_, RorConfig(_, _, _, Some(auditingSettings)))) =>
      auditingSettings.auditSinks.size should be(1)

      val headSink = auditingSettings.auditSinks.head
      headSink shouldBe a[AuditSink.Enabled]

      val headSinkConfig = headSink.asInstanceOf[AuditSink.Enabled].config
      headSinkConfig shouldBe a[Config.LogBasedSink]

      val sinkConfig = headSinkConfig.asInstanceOf[Config.LogBasedSink]
      sinkConfig.loggerName should be(RorAuditLoggerName(expectedLoggerName))
      sinkConfig.logSerializer shouldBe a[EXPECTED_SERIALIZER]
    }
  }

  private def assertInvalidSettings(config: RawRorConfig,
                                    expectedErrorMessage: String,
                                    esVersion: EsVersion = defaultEsVersionForTests): Unit = {
    val core = factory(esVersion)
      .createCoreFrom(
        config,
        RorConfigurationIndex(IndexName.Full(".readonlyrest")),
        MockHttpClientsFactory,
        MockLdapConnectionPoolProvider,
        NoOpMocksProvider
      )
      .runSyncUnsafe()
    inside(core) { case Left(errors) =>
      errors.length should be(1)
      errors.head should be(AuditingSettingsCreationError(Message(expectedErrorMessage)))
    }
  }

  private def circeJsonWithIgnoredTimestamp(json: JSONObject): Option[Json] = {
    json
      .withTimestampValue("IGNORED")
      .circeJsonE
      .toOption
  }

  extension (jsonObject: JSONObject) {
    private def withTimestampValue(value: String): JSONObject = {
      jsonObject.put("@timestamp", value)
    }
    private def circeJsonE: Either[String, Json] =
      parser.parse(jsonObject.toString(0)).left.map(_.getMessage)
  }

}

private class TestEnvironmentAwareAuditLogSerializer extends EnvironmentAwareAuditLogSerializer {

  def onResponse(responseContext: AuditResponseContext,
                 environmentContext: AuditEnvironmentContext): Option[JSONObject] = Some(
    new JSONObject()
      .put("custom_field_for_es_node_name", environmentContext.esNodeName)
      .put("custom_field_for_es_cluster_name", environmentContext.esClusterName)
  )

}

private class DummyAuditRequestContext(override val loggedInUserName: Option[String] = Some("logged_user"),
                                       override val attemptedUserName: Option[String] = Some("basic auth user")) extends AuditRequestContext {
  override def timestamp: Instant = Instant.now().minusSeconds(5)

  override def id: String = "trace_id_123"

  override def correlationId: String = "corr_id_123"

  override def indices: Set[String] = Set("a1", "a2", "b1", "b2")

  override def action: String = "cluster:internal_ror/user_metadata/get"

  override def headers: Map[String, String] = Map("HEADER1" -> "HVALUE1", "HEADER2" -> "HVALUE2")

  override def requestHeaders: Headers = Headers(headers.view.mapValues(v => Set(v)).toMap)

  override def uriPath: String = "/path/to/resource"

  override def history: String = "historyEntry1, historyEntry2"

  override def content: String = "Full content of the request"

  override def contentLength: Integer = 123

  override def remoteAddress: String = "192.168.0.123"

  override def localAddress: String = "192.168.0.124"

  override def `type`: String = "RRTestConfigRequest"

  override def taskId: Long = 123

  override def httpMethod: String = "GET"

  override def impersonatedByUserName: Option[String] = Some("impersonated_by_user")

  override def involvesIndices: Boolean = false

  override def rawAuthHeader: Option[String] = Some("Bearer 123")

  override def generalAuditEvents: JSONObject = new JSONObject

  override def auditEnvironmentContext: AuditEnvironmentContext = testAuditEnvironmentContext
}
