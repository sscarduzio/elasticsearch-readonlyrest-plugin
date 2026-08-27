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

import better.files.*
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.{Json, parser}
import io.lemonlabs.uri.Uri
import monix.execution.Scheduler.Implicits.global
import org.json.JSONObject
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{Assertion, Inside}
import squants.information.Megabytes
import tech.beshu.ror.SystemContext
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditOutputConfig.*
import tech.beshu.ror.accesscontrol.audit.AuditingTool.AuditOutputs.Configured
import tech.beshu.ror.accesscontrol.audit.AuditingTool.{AuditOutputs, AuditingConfig}
import tech.beshu.ror.accesscontrol.audit.EsAuditCapabilities
import tech.beshu.ror.accesscontrol.audit.EsAuditCapabilities.IndexOnly
import tech.beshu.ror.accesscontrol.audit.ecs.EcsV1AuditLogSerializer
import tech.beshu.ror.accesscontrol.audit.{
  AuditEnvironmentContextBasedOnEsNodeSettings,
  AuditFieldUtils,
  AuditSerializer
}
import tech.beshu.ror.accesscontrol.blocks.mocks.NoOpMocksProvider
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.AuditCluster.*
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.AuditingSettingsCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.{Core, RawRorSettingsBasedCoreFactory, RorDependencies}
import tech.beshu.ror.audit.*
import tech.beshu.ror.audit.AuditResponseContext.Verbosity
import tech.beshu.ror.audit.adapters.{DeprecatedAuditLogSerializerAdapter, EnvironmentAwareAuditLogSerializerAdapter}
import tech.beshu.ror.audit.instances.*
import tech.beshu.ror.audit.utils.AuditSerializationHelper.{AllowedEventMode, AuditFieldPath, AuditFieldValueDescriptor}
import tech.beshu.ror.es.EsEnv
import tech.beshu.ror.mocks.{
  MockHttpClientsFactory,
  MockIndexBasedAuditOutputServiceCreator,
  MockLdapConnectionPoolProvider,
  MockedCapabilities
}
import tech.beshu.ror.settings.ror.RawRorSettings
import tech.beshu.ror.utils.RefinedUtils.positiveInt
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

import java.time.{Instant, ZoneId, ZonedDateTime}
import scala.reflect.ClassTag

class AuditingConfigTests extends AnyWordSpec with Inside {

  private val zonedDateTime = ZonedDateTime.of(2019, 1, 1, 0, 1, 59, 0, ZoneId.of("+1"))

  private val defaultRemoteClusterMode = ClusterMode.RoundRobin

  "Audit settings" when {
    "audit is not configured" should {
      "be disabled by default" in {
        val settings = rorSettingsFromUnsafe(
          """
            |readonlyrest:
            |
            |  access_control_rules:
            |
            |  - name: test_block
            |    type: allow
            |    auth_key: admin:container
            |
          """.stripMargin
        )

        assertOutputsDisabled(settings)
      }
      "have defaultAclLog enabled by default" in {
        val settings = rorSettingsFromUnsafe(
          """
            |readonlyrest:
            |
            |  access_control_rules:
            |
            |  - name: test_block
            |    type: allow
            |    auth_key: admin:container
            |
          """.stripMargin
        )

        val core = factory()
          .createCoreFrom(
            settings,
            RorSettingsIndex(IndexName.Full(".readonlyrest")),
            MockHttpClientsFactory,
            MockLdapConnectionPoolProvider,
            NoOpMocksProvider,
            MockedCapabilities.indexOrDataStream
          )
          .map(_.map(_.core))
          .runSyncUnsafe()
        inside(core) { case Right(Core(_, RorDependencies(_, _, _), AuditingConfig(AuditOutputs.Disabled, true, _))) =>
        }
      }
    }
    "audit is disabled" should {
      "be disabled" when {
        "one line audit format" in {
          val settings = rorSettingsFromUnsafe(
            """
              |readonlyrest.audit.enabled: false
              |readonlyrest:
              |  access_control_rules:
              |
              |  - name: test_block
              |    type: allow
              |    auth_key: admin:container
            """.stripMargin
          )

          assertOutputsDisabled(settings)
        }
        "multi line audit format" in {
          val settings = rorSettingsWithAuditUnsafe(
            """
              |  audit:
              |    enabled: false
            """.stripMargin
          )

          assertOutputsDisabled(settings)
        }
        "flat dot-notation audit.enabled key inside readonlyrest block" in {
          val settings = rorSettingsWithAuditUnsafe(
            "audit.enabled: false"
          )

          assertOutputsDisabled(settings)
        }
      }
    }
    "audit settings contain conflicting keys" should {
      "be rejected when both nested 'audit: enabled' block and flat 'audit.enabled' key are present inside readonlyrest" in {
        val settings = rorSettingsWithAuditUnsafe(
          """
            |  audit:
            |    enabled: true
            |  audit.enabled: true
          """.stripMargin
        )

        assertInvalidSettings(
          settings,
          expectedErrorMessage =
            "Duplicated audit 'enabled' setting: use either the nested form 'audit: {enabled: ...}' or the flat form 'audit.enabled', not both"
        )
      }
    }
    "audit is enabled" should {
      "be able to be loaded from settings" when {
        "no outputs defined" should {
          "return Defaults from decoder (defaults applied at runtime)" when {
            "one line audit format" in {
              val settings = rorSettingsFromUnsafe(
                """
                  |readonlyrest.audit.enabled: true
                  |readonlyrest:
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |""".stripMargin
              )

              assertDefaultOutputs(settings)
            }
            "multi line audit format" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                """.stripMargin
              )

              assertDefaultOutputs(settings)
            }
            "flat dot-notation audit.enabled key inside readonlyrest block" in {
              val settings = rorSettingsWithAuditUnsafe(
                "audit.enabled: true"
              )

              assertDefaultOutputs(settings)
            }
          }
        }
        "simple format is used" in {
          val settings = rorSettingsWithAuditUnsafe(
            """
              |  audit:
              |    enabled: true
              |    outputs: [index, log, data_stream]
            """.stripMargin
          )

          val core = factory()
            .createCoreFrom(
              settings,
              RorSettingsIndex(IndexName.Full(".readonlyrest")),
              MockHttpClientsFactory,
              MockLdapConnectionPoolProvider,
              NoOpMocksProvider,
              MockedCapabilities.indexOrDataStream
            )
            .map(_.map(_.core))
            .runSyncUnsafe()
          inside(core) {
            case Right(Core(_, RorDependencies(_, _, _), AuditingConfig(Configured(auditOutputs), _, _))) =>
              auditOutputs.size should be(3)

              val output1 = auditOutputs.head
              output1 shouldBe a[EsIndexBased]
              val output1Config = output1.asInstanceOf[EsIndexBased].config
              output1Config.rorAuditIndexTemplate.indexName(zonedDateTime.toInstant) should be(
                indexName("readonlyrest_audit-2018-12-31")
              )
              output1Config.serializer
                .asInstanceOf[AuditSerializer.Delegating]
                .serializer shouldBe a[BlockVerbosityAwareAuditLogSerializer]
              output1Config.auditCluster shouldBe AuditCluster.LocalAuditCluster

              val output2 = auditOutputs.toList(1)
              output2 shouldBe a[LogBased]
              val output2Config = output2.asInstanceOf[LogBased].config
              output2Config.loggerName should be(RorAuditLoggerName("readonlyrest_audit"))
              output2Config.serializer
                .asInstanceOf[AuditSerializer.Delegating]
                .serializer shouldBe a[BlockVerbosityAwareAuditLogSerializer]

              val output3 = auditOutputs.toList(2)
              output3 shouldBe a[EsDataStreamBased]
              val output3Config = output3.asInstanceOf[EsDataStreamBased].config
              output3Config.rorAuditDataStream.dataStream should be(fullDataStreamName("readonlyrest_audit"))
              output3Config.serializer
                .asInstanceOf[AuditSerializer.Delegating]
                .serializer shouldBe a[BlockVerbosityAwareAuditLogSerializer]
              output3Config.auditCluster shouldBe AuditCluster.LocalAuditCluster
          }
        }
        "'log' output type defined" when {
          "only type is set" in {
            val settings = rorSettingsWithAuditUnsafe(
              """
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: log
              """.stripMargin
            )

            assertLogBasedAuditOutputConfigPresent[BlockVerbosityAwareAuditLogSerializer](
              settings,
              expectedLoggerName = "readonlyrest_audit"
            )
          }
          "the output's enabled flag is set" when {
            "set to true" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: log
                  |      enabled: true
                """.stripMargin
              )

              assertLogBasedAuditOutputConfigPresent[BlockVerbosityAwareAuditLogSerializer](
                settings,
                expectedLoggerName = "readonlyrest_audit"
              )
            }
            "set to false" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: log
                  |      enabled: false
                """.stripMargin
              )

              assertOutputsDisabled(settings)
            }
          }
          "custom logger name is set" in {
            val settings = rorSettingsWithAuditUnsafe(
              """
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: log
                |      logger_name: custom_logger
              """.stripMargin
            )

            assertLogBasedAuditOutputConfigPresent[BlockVerbosityAwareAuditLogSerializer](
              settings,
              expectedLoggerName = "custom_logger"
            )
          }
          "custom serializer is set" in {
            val settings = rorSettingsWithAuditUnsafe(
              """
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: log
                |      serializer: "tech.beshu.ror.audit.instances.QueryAuditLogSerializer"
              """.stripMargin
            )

            assertLogBasedAuditOutputConfigPresent[QueryAuditLogSerializer](
              settings,
              expectedLoggerName = "readonlyrest_audit"
            )
          }
          "deprecated custom serializer is set" in {
            val settings = rorSettingsWithAuditUnsafe(
              """
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: log
                |      serializer: "tech.beshu.ror.requestcontext.QueryAuditLogSerializer"
              """.stripMargin
            )

            assertLogBasedAuditOutputConfigPresent[DeprecatedAuditLogSerializerAdapter[_]](
              settings,
              expectedLoggerName = "readonlyrest_audit"
            )
          }
          "all custom settings are set" in {
            val settings = rorSettingsWithAuditUnsafe(
              """
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: log
                |      logger_name: custom_logger
                |      serializer: "tech.beshu.ror.audit.instances.QueryAuditLogSerializer"
              """.stripMargin
            )

            assertLogBasedAuditOutputConfigPresent[QueryAuditLogSerializer](
              settings,
              expectedLoggerName = "custom_logger"
            )
          }
          "file_appender section is set" in {
            val settings = rorSettingsWithAuditUnsafe(
              """
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: log
                |      file_appender:
                |        file_path: /tmp/ror-audit-test.log
                |        max_file_size: 100MB
                |        max_files: 7
              """.stripMargin
            )

            assertLogBasedAuditOutputFileConfigPresent(
              settings,
              expectedLoggerName = "readonlyrest_audit",
              expectedFileAppender = RollingFileBased.FileAppender(
                filePath = java.nio.file.Paths.get("/tmp/ror-audit-test.log"),
                maxFileSize = Megabytes(100),
                maxFiles = positiveInt(7)
              )
            )
          }
          "file_appender section with custom rotation settings is set" in {
            val settings = rorSettingsWithAuditUnsafe(
              """
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: log
                |      file_appender:
                |        file_path: /tmp/ror-audit-test.log
                |        max_file_size: 50MB
                |        max_files: 3
              """.stripMargin
            )

            assertLogBasedAuditOutputFileConfigPresent(
              settings,
              expectedLoggerName = "readonlyrest_audit",
              expectedFileAppender = RollingFileBased.FileAppender(
                filePath = java.nio.file.Paths.get("/tmp/ror-audit-test.log"),
                maxFileSize = Megabytes(50),
                maxFiles = positiveInt(3)
              )
            )
          }
          "file_appender max_file_size rejects mixed-case bit-unit suffix (Mb)" in {
            // squants 1.8.3 uses "Mbit" for megabits, not "Mb" — "Mb" is not a valid symbol
            // and must produce an error rather than silently parse as megabits (8x smaller than MB).
            val settings = rorSettingsWithAuditUnsafe(
              """
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: log
                |      file_appender:
                |        file_path: /tmp/ror-audit-test.log
                |        max_file_size: 100Mb
                |        max_files: 7
              """.stripMargin
            )
            assertInvalidSettings(
              settings,
              expectedErrorMessage =
                "Invalid audit 'max_file_size': Cannot parse '100Mb' as a data size. Expected format like '1 MB', '512 KB'"
            )
          }
          "file_appender max_file_size rejects all-lowercase unit suffix (mb)" in {
            val settings = rorSettingsWithAuditUnsafe(
              """
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: log
                |      file_appender:
                |        file_path: /tmp/ror-audit-test.log
                |        max_file_size: 100mb
                |        max_files: 7
              """.stripMargin
            )
            assertInvalidSettings(
              settings,
              expectedErrorMessage =
                "Invalid audit 'max_file_size': Cannot parse '100mb' as a data size. Expected format like '1 MB', '512 KB'"
            )
          }
          "configurable serializer is set" in {
            val settings = rorSettingsWithAuditUnsafe(
              """
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
                |          matched_blocks: "{MATCHED_BLOCK_NAMES}"
                |          tid: "{TASK_ID}"
                |          bytes: "{CONTENT_LENGTH_IN_BYTES}"
              """.stripMargin
            )

            assertLogBasedAuditOutputConfigPresent[AuditSerializer.Configurable](
              settings,
              expectedLoggerName = "readonlyrest_audit"
            )

            val configuredSerializer = serializer(settings).asInstanceOf[AuditSerializer.Configurable]

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
              AuditFieldPath("node_name_with_static_suffix") -> AuditFieldValueDescriptor.Combined(
                List(AuditFieldValueDescriptor.EsNodeName, AuditFieldValueDescriptor.StaticText(" with suffix"))
              ),
              AuditFieldPath("another_field") -> AuditFieldValueDescriptor.Combined(
                List(
                  AuditFieldValueDescriptor.EsClusterName,
                  AuditFieldValueDescriptor.StaticText(" "),
                  AuditFieldValueDescriptor.HttpMethod
                )
              ),
              AuditFieldPath("matched_blocks") -> AuditFieldValueDescriptor.MatchedBlockNames,
              AuditFieldPath("tid") -> AuditFieldValueDescriptor.TaskId,
              AuditFieldPath("bytes") -> AuditFieldValueDescriptor.ContentLengthInBytes,
            )
          }
          "configurable serializer with allowed_events_serialization_mode: based_on_block_settings" in {
            val settings = rorSettingsWithAuditUnsafe(
              """
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: log
                |      serializer:
                |        type: configurable
                |        allowed_events_serialization_mode: based_on_block_settings
                |        fields:
                |          message: "test"
              """.stripMargin
            )

            val configuredSerializer = serializer(settings).asInstanceOf[AuditSerializer.Configurable]
            configuredSerializer.allowedEventMode shouldBe AllowedEventMode.Include(Set(Verbosity.Info))
          }
          "configurable serializer with allowed_events_serialization_mode: always" in {
            val settings = rorSettingsWithAuditUnsafe(
              """
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: log
                |      serializer:
                |        type: configurable
                |        allowed_events_serialization_mode: always
                |        fields:
                |          message: "test"
              """.stripMargin
            )

            val configuredSerializer = serializer(settings).asInstanceOf[AuditSerializer.Configurable]
            configuredSerializer.allowedEventMode shouldBe AllowedEventMode.IncludeAll
          }
        }
        "'index' output type defined" when {
          "only type is set" in {
            val settings = rorSettingsWithAuditUnsafe(
              """
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: index
              """.stripMargin
            )

            assertIndexBasedAuditOutputConfigPresent[BlockVerbosityAwareAuditLogSerializer](
              settings,
              expectedIndexName = "readonlyrest_audit-2018-12-31",
              expectedAuditCluster = LocalAuditCluster
            )
          }
          "the output's enabled flag is set" when {
            "set to true" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: index
                  |      enabled: true
                """.stripMargin
              )

              assertIndexBasedAuditOutputConfigPresent[BlockVerbosityAwareAuditLogSerializer](
                settings,
                expectedIndexName = "readonlyrest_audit-2018-12-31",
                expectedAuditCluster = LocalAuditCluster
              )
            }
            "set to false" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: index
                  |      enabled: false
                """.stripMargin
              )

              assertOutputsDisabled(settings)
            }
          }
          "custom audit index name is set" in {
            val settings = rorSettingsWithAuditUnsafe(
              """
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: index
                |      index_template: "'custom_template_'yyyyMMdd"
              """.stripMargin
            )

            assertIndexBasedAuditOutputConfigPresent[BlockVerbosityAwareAuditLogSerializer](
              settings,
              expectedIndexName = "custom_template_20181231",
              expectedAuditCluster = LocalAuditCluster
            )
          }
          "serializer is set" when {
            "QueryAuditLogSerializer serializer is set" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: index
                  |      serializer: "tech.beshu.ror.audit.instances.QueryAuditLogSerializer"
                """.stripMargin
              )

              assertIndexBasedAuditOutputConfigPresent[QueryAuditLogSerializer](
                settings,
                expectedIndexName = "readonlyrest_audit-2018-12-31",
                expectedAuditCluster = LocalAuditCluster
              )
            }
            "QueryAuditLogSerializer serializer is set and correctly serializes event without logged user" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: index
                  |      serializer: "tech.beshu.ror.audit.instances.QueryAuditLogSerializer"
                """.stripMargin
              )

              assertIndexBasedAuditOutputConfigPresent[QueryAuditLogSerializer](
                settings,
                expectedIndexName = "readonlyrest_audit-2018-12-31",
                expectedAuditCluster = LocalAuditCluster
              )
              val createdSerializer = auditLogSerializer(settings)
              val serializedResponse = createdSerializer.onResponse(
                AuditResponseContext.Forbidden(
                  new DummyAuditRequestContext(loggedInUserName = None, attemptedUserName = None)
                )
              )

              serializedResponse shouldBe defined
              serializedResponse.get.get("user") shouldBe "Bearer 123"
              serializedResponse.get.isNull("presented_identity")
              serializedResponse.get.isNull("logged_user")
            }
            "QueryAuditLogSerializer serializer is set and correctly serializes event with logged user" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: index
                  |      serializer: "tech.beshu.ror.audit.instances.QueryAuditLogSerializer"
                """.stripMargin
              )

              assertIndexBasedAuditOutputConfigPresent[QueryAuditLogSerializer](
                settings,
                expectedIndexName = "readonlyrest_audit-2018-12-31",
                expectedAuditCluster = LocalAuditCluster
              )
              val createdSerializer = auditLogSerializer(settings)
              val serializedResponse = createdSerializer.onResponse(
                AuditResponseContext.Forbidden(new DummyAuditRequestContext(loggedInUserName = Some("my_user")))
              )

              serializedResponse shouldBe defined
              serializedResponse.get.get("user") shouldBe "my_user"
              serializedResponse.get.get("presented_identity") shouldBe "basic auth user"
              serializedResponse.get.get("logged_user") shouldBe "my_user"
            }
            "custom environment-aware serializer is set and correctly serializes events" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: data_stream
                  |      serializer: "tech.beshu.ror.unit.acl.factory.TestEnvironmentAwareAuditLogSerializer"
                """.stripMargin
              )

              assertDataStreamAuditOutputConfigPresent[EnvironmentAwareAuditLogSerializerAdapter](
                settings,
                expectedDataStreamName = "readonlyrest_audit",
                expectedAuditCluster = LocalAuditCluster,
              )
              val createdSerializer = auditLogSerializer(settings)
              val serializedResponse =
                createdSerializer.onResponse(AuditResponseContext.Forbidden(new DummyAuditRequestContext))

              serializedResponse shouldBe defined
              serializedResponse.get.get("custom_field_for_es_node_name") shouldBe "testEsNode"
              serializedResponse.get.get("custom_field_for_es_cluster_name") shouldBe "testEsCluster"
            }
            "ECS serializer is set (including request content)" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: index
                  |      serializer:
                  |        type: ecs
                  |        verbosity_level_serialization_mode: [INFO]
                  |        include_full_request_content: true
                """.stripMargin
              )

              assertIndexBasedEcsAuditOutputConfigPresent(
                settings,
                expectedIndexName = "readonlyrest_audit-2018-12-31",
                expectedAuditCluster = LocalAuditCluster
              )
              val createdSerializer = ecsSerializer(settings)
              val serializedResponse = EcsV1AuditLogSerializer.onResponse(
                AuditResponseContext.Forbidden(new DummyAuditRequestContext),
                createdSerializer.allowedEventMode,
                createdSerializer.includeFullRequestContent
              )

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
                  |    "ror_detailed_reason" : "mismatched",
                  |    "ror_involved_indices" : [],
                  |    "presented_identity" : "basic auth user",
                  |    "ror_final_state" : "FORBIDDEN",
                  |    "ror_matched_block_names" : ["block1", "block2"]
                  |  }
                  |}""".stripMargin
              val actualJson = serializedResponse.flatMap(circeJsonWithIgnoredTimestamp)
              val expectedJson = circeJsonWithIgnoredTimestamp(new JSONObject(expectedJsonStr))
              actualJson should be(expectedJson)
            }
            "ECS serializer is set (not including request content)" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: index
                  |      serializer:
                  |        type: ecs
                  |        verbosity_level_serialization_mode: [INFO]
                  |        include_full_request_content: false
                """.stripMargin
              )

              assertIndexBasedEcsAuditOutputConfigPresent(
                settings,
                expectedIndexName = "readonlyrest_audit-2018-12-31",
                expectedAuditCluster = LocalAuditCluster
              )
              val createdSerializer = ecsSerializer(settings)
              val serializedResponse = EcsV1AuditLogSerializer.onResponse(
                AuditResponseContext.Forbidden(new DummyAuditRequestContext),
                createdSerializer.allowedEventMode,
                createdSerializer.includeFullRequestContent
              )

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
                  |    "ror_detailed_reason" : "mismatched",
                  |    "ror_involved_indices" : [],
                  |    "presented_identity" : "basic auth user",
                  |    "ror_final_state" : "FORBIDDEN",
                  |    "ror_matched_block_names" : ["block1", "block2"]
                  |  }
                  |}""".stripMargin
              val actualJson = serializedResponse.flatMap(circeJsonWithIgnoredTimestamp)
              val expectedJson = circeJsonWithIgnoredTimestamp(new JSONObject(expectedJsonStr))
              actualJson should be(expectedJson)
            }
            "ECS serializer is set (not including request content by default)" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: index
                  |      serializer:
                  |        type: ecs
                  |        verbosity_level_serialization_mode: [INFO]
                """.stripMargin
              )

              assertIndexBasedEcsAuditOutputConfigPresent(
                settings,
                expectedIndexName = "readonlyrest_audit-2018-12-31",
                expectedAuditCluster = LocalAuditCluster
              )
              val createdSerializer = ecsSerializer(settings)
              val serializedResponse = EcsV1AuditLogSerializer.onResponse(
                AuditResponseContext.Forbidden(new DummyAuditRequestContext),
                createdSerializer.allowedEventMode,
                createdSerializer.includeFullRequestContent
              )

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
                  |    "ror_detailed_reason" : "mismatched",
                  |    "ror_involved_indices" : [],
                  |    "presented_identity" : "basic auth user",
                  |    "ror_final_state" : "FORBIDDEN",
                  |    "ror_matched_block_names" : ["block1", "block2"]
                  |  }
                  |}""".stripMargin
              val actualJson = serializedResponse.flatMap(circeJsonWithIgnoredTimestamp)
              val expectedJson = circeJsonWithIgnoredTimestamp(new JSONObject(expectedJsonStr))
              actualJson should be(expectedJson)
            }
            "ECS serializer with allowed_events_serialization_mode: based_on_block_settings" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: index
                  |      serializer:
                  |        type: ecs
                  |        allowed_events_serialization_mode: based_on_block_settings
                """.stripMargin
              )

              val createdSerializer = ecsSerializer(settings)
              createdSerializer.allowedEventMode shouldBe AllowedEventMode
                .Include(Set(Verbosity.Info))
            }
            "ECS serializer with allowed_events_serialization_mode: always" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: index
                  |      serializer:
                  |        type: ecs
                  |        allowed_events_serialization_mode: always
                """.stripMargin
              )

              val createdSerializer = ecsSerializer(settings)
              createdSerializer.allowedEventMode shouldBe AllowedEventMode.IncludeAll
            }
            "deprecated custom serializer is set" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: index
                  |      serializer: "tech.beshu.ror.requestcontext.QueryAuditLogSerializer"
                """.stripMargin
              )

              assertIndexBasedAuditOutputConfigPresent[DeprecatedAuditLogSerializerAdapter[_]](
                settings,
                expectedIndexName = "readonlyrest_audit-2018-12-31",
                expectedAuditCluster = LocalAuditCluster
              )
            }
          }
          "audit cluster is set" when {
            "array syntax for cluster" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: index
                  |      cluster: ["1.1.1.1"]
                """.stripMargin
              )
              assertIndexBasedAuditOutputConfigPresent[BlockVerbosityAwareAuditLogSerializer](
                settings,
                expectedIndexName = "readonlyrest_audit-2018-12-31",
                expectedAuditCluster = RemoteAuditCluster(
                  nodes = UniqueNonEmptyList.of(AuditClusterNode(Uri.parse("1.1.1.1"))),
                  mode = defaultRemoteClusterMode,
                  credentials = None
                )
              )
            }
            "array syntax for cluster with credentials" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: index
                  |      cluster: [ "https://user:pass@1.1.1.1:9200", "https://user:pass@2.2.2.2:9200" ]
                """.stripMargin
              )
              assertIndexBasedAuditOutputConfigPresent[BlockVerbosityAwareAuditLogSerializer](
                settings,
                expectedIndexName = "readonlyrest_audit-2018-12-31",
                expectedAuditCluster = RemoteAuditCluster(
                  nodes = UniqueNonEmptyList.of(
                    AuditClusterNode(Uri.parse("https://user:pass@1.1.1.1:9200")),
                    AuditClusterNode(Uri.parse("https://user:pass@2.2.2.2:9200"))
                  ),
                  mode = ClusterMode.RoundRobin,
                  credentials = Some(NodeCredentials("user", "pass"))
                )
              )
            }
            "extended syntax for cluster with round-robin mode" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: index
                  |      cluster:
                  |        nodes: ["1.1.1.1"]
                  |        mode: round-robin
                """.stripMargin
              )
              assertIndexBasedAuditOutputConfigPresent[BlockVerbosityAwareAuditLogSerializer](
                settings,
                expectedIndexName = "readonlyrest_audit-2018-12-31",
                expectedAuditCluster = RemoteAuditCluster(
                  nodes = UniqueNonEmptyList.of(AuditClusterNode(Uri.parse("1.1.1.1"))),
                  mode = ClusterMode.RoundRobin,
                  credentials = None
                )
              )
            }
            "extended syntax for cluster with credentials" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: index
                  |      cluster:
                  |        nodes: ["1.1.1.1", "2.2.2.2", "3.3.3.3"]
                  |        mode: round-robin
                  |        username: "user"
                  |        password: "pass"
                """.stripMargin
              )
              assertIndexBasedAuditOutputConfigPresent[BlockVerbosityAwareAuditLogSerializer](
                settings,
                expectedIndexName = "readonlyrest_audit-2018-12-31",
                expectedAuditCluster = RemoteAuditCluster(
                  nodes = UniqueNonEmptyList.of(
                    AuditClusterNode(Uri.parse("1.1.1.1")),
                    AuditClusterNode(Uri.parse("2.2.2.2")),
                    AuditClusterNode(Uri.parse("3.3.3.3"))
                  ),
                  mode = ClusterMode.RoundRobin,
                  credentials = Some(NodeCredentials("user", "pass"))
                )
              )
            }
          }
          "all audit settings are custom" in {
            val settings = rorSettingsWithAuditUnsafe(
              """
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: index
                |      index_template: "'custom_template_'yyyyMMdd"
                |      serializer: "tech.beshu.ror.audit.instances.QueryAuditLogSerializer"
                |      cluster: ["1.1.1.1"]
              """.stripMargin
            )

            assertIndexBasedAuditOutputConfigPresent[QueryAuditLogSerializer](
              settings,
              expectedIndexName = "custom_template_20181231",
              expectedAuditCluster = RemoteAuditCluster(
                nodes = UniqueNonEmptyList.of(AuditClusterNode(Uri.parse("1.1.1.1"))),
                mode = ClusterMode.RoundRobin,
                credentials = None
              )
            )
          }
        }
        "'data_stream' output type defined" when {
          "only type is set" in {
            val settings = rorSettingsWithAuditUnsafe(
              """
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: data_stream
              """.stripMargin
            )

            assertDataStreamAuditOutputConfigPresent[BlockVerbosityAwareAuditLogSerializer](
              settings,
              expectedDataStreamName = "readonlyrest_audit",
              expectedAuditCluster = LocalAuditCluster
            )
          }
          "the output's enabled flag is set" when {
            "set to true" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: data_stream
                  |      enabled: true
                """.stripMargin
              )

              assertDataStreamAuditOutputConfigPresent[BlockVerbosityAwareAuditLogSerializer](
                settings,
                expectedDataStreamName = "readonlyrest_audit",
                expectedAuditCluster = LocalAuditCluster
              )
            }
            "set to false" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: data_stream
                  |      enabled: false
                """.stripMargin
              )

              assertOutputsDisabled(settings)
            }
          }
          "custom audit data stream name is set" in {
            val settings = rorSettingsWithAuditUnsafe(
              """
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: data_stream
                |      data_stream: "custom_audit_data_stream"
              """.stripMargin
            )

            assertDataStreamAuditOutputConfigPresent[BlockVerbosityAwareAuditLogSerializer](
              settings,
              expectedDataStreamName = "custom_audit_data_stream",
              expectedAuditCluster = LocalAuditCluster
            )
          }
          "serializer is set" when {
            "custom serializer is set" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: data_stream
                  |      serializer: "tech.beshu.ror.audit.instances.QueryAuditLogSerializer"
                """.stripMargin
              )

              assertDataStreamAuditOutputConfigPresent[QueryAuditLogSerializer](
                settings,
                expectedDataStreamName = "readonlyrest_audit",
                expectedAuditCluster = LocalAuditCluster
              )
            }
            "deprecated custom serializer is set" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: data_stream
                  |      serializer: "tech.beshu.ror.requestcontext.QueryAuditLogSerializer"
                """.stripMargin
              )

              assertDataStreamAuditOutputConfigPresent[DeprecatedAuditLogSerializerAdapter[_]](
                settings,
                expectedDataStreamName = "readonlyrest_audit",
                expectedAuditCluster = LocalAuditCluster
              )
            }
          }
          "audit cluster is set" when {
            "array syntax for cluster" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: data_stream
                  |      cluster: ["1.1.1.1"]
                """.stripMargin
              )
              assertDataStreamAuditOutputConfigPresent[BlockVerbosityAwareAuditLogSerializer](
                settings,
                expectedDataStreamName = "readonlyrest_audit",
                expectedAuditCluster = RemoteAuditCluster(
                  nodes = UniqueNonEmptyList.of(AuditClusterNode(Uri.parse("1.1.1.1"))),
                  mode = ClusterMode.RoundRobin,
                  credentials = None
                )
              )
            }
            "array syntax for cluster with credentials" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: data_stream
                  |      cluster: [ "https://user:pass@1.1.1.1:9200" ]
                """.stripMargin
              )
              assertDataStreamAuditOutputConfigPresent[BlockVerbosityAwareAuditLogSerializer](
                settings,
                expectedDataStreamName = "readonlyrest_audit",
                expectedAuditCluster = RemoteAuditCluster(
                  nodes = UniqueNonEmptyList.of(AuditClusterNode(Uri.parse("https://user:pass@1.1.1.1:9200"))),
                  mode = ClusterMode.RoundRobin,
                  credentials = Some(NodeCredentials("user", "pass"))
                )
              )
            }
            "extended syntax for cluster with round-robin mode" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: data_stream
                  |      cluster:
                  |        nodes: ["1.1.1.1"]
                  |        mode: round-robin
                """.stripMargin
              )
              assertDataStreamAuditOutputConfigPresent[BlockVerbosityAwareAuditLogSerializer](
                settings,
                expectedDataStreamName = "readonlyrest_audit",
                expectedAuditCluster = RemoteAuditCluster(
                  nodes = UniqueNonEmptyList.of(AuditClusterNode(Uri.parse("1.1.1.1"))),
                  mode = ClusterMode.RoundRobin,
                  credentials = None
                )
              )
            }
            "extended syntax for cluster with credentials" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: data_stream
                  |      cluster:
                  |        nodes: ["1.1.1.1", "2.2.2.2", "3.3.3.3"]
                  |        mode: round-robin
                  |        username: "user"
                  |        password: "pass"
                """.stripMargin
              )
              assertDataStreamAuditOutputConfigPresent[BlockVerbosityAwareAuditLogSerializer](
                settings,
                expectedDataStreamName = "readonlyrest_audit",
                expectedAuditCluster = RemoteAuditCluster(
                  nodes = UniqueNonEmptyList.of(
                    AuditClusterNode(Uri.parse("1.1.1.1")),
                    AuditClusterNode(Uri.parse("2.2.2.2")),
                    AuditClusterNode(Uri.parse("3.3.3.3"))
                  ),
                  mode = ClusterMode.RoundRobin,
                  credentials = Some(NodeCredentials("user", "pass"))
                )
              )
            }
          }
          "all audit settings are custom" in {
            val settings = rorSettingsWithAuditUnsafe(
              """
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
              """.stripMargin
            )

            assertDataStreamAuditOutputConfigPresent[QueryAuditLogSerializer](
              settings,
              expectedDataStreamName = "custom_audit_data_stream",
              expectedAuditCluster = RemoteAuditCluster(
                nodes = UniqueNonEmptyList.of(
                  AuditClusterNode(Uri.parse("1.1.1.1")),
                  AuditClusterNode(Uri.parse("2.2.2.2")),
                ),
                mode = ClusterMode.RoundRobin,
                credentials = Some(NodeCredentials("user", "pass"))
              )
            )
          }
          "the ES module supports data streams" in {
            val settings = rorSettingsWithAuditUnsafe(
              """
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: data_stream
                |      data_stream: "custom_audit_data_stream"
                |      serializer: "tech.beshu.ror.audit.instances.QueryAuditLogSerializer"
                |      cluster: ["1.1.1.1"]
              """.stripMargin
            )

            assertDataStreamAuditOutputConfigPresent[QueryAuditLogSerializer](
              settings,
              expectedDataStreamName = "custom_audit_data_stream",
              expectedAuditCluster = RemoteAuditCluster(
                nodes = UniqueNonEmptyList.of(AuditClusterNode(Uri.parse("1.1.1.1"))),
                mode = ClusterMode.RoundRobin,
                credentials = None
              ),
            )
          }
        }

        "all output types defined" in {
          val settings = rorSettingsWithAuditUnsafe(
            """
              |  audit:
              |    enabled: true
              |    outputs:
              |    - type: index
              |    - type: log
              |      serializer: "tech.beshu.ror.audit.instances.QueryAuditLogSerializer"
              |    - type: data_stream
            """.stripMargin
          )

          val core = factory()
            .createCoreFrom(
              settings,
              RorSettingsIndex(IndexName.Full(".readonlyrest")),
              MockHttpClientsFactory,
              MockLdapConnectionPoolProvider,
              NoOpMocksProvider,
              MockedCapabilities.indexOrDataStream
            )
            .map(_.map(_.core))
            .runSyncUnsafe()
          inside(core) {
            case Right(Core(_, RorDependencies(_, _, _), AuditingConfig(Configured(auditOutputs), _, _))) =>
              auditOutputs.size should be(3)

              val output1 = auditOutputs.head
              output1 shouldBe a[EsIndexBased]
              val output1Config = output1.asInstanceOf[EsIndexBased].config
              output1Config.rorAuditIndexTemplate.indexName(zonedDateTime.toInstant) should be(
                indexName("readonlyrest_audit-2018-12-31")
              )
              output1Config.serializer
                .asInstanceOf[AuditSerializer.Delegating]
                .serializer shouldBe a[BlockVerbosityAwareAuditLogSerializer]
              output1Config.auditCluster shouldBe AuditCluster.LocalAuditCluster

              val output2 = auditOutputs.toList(1)
              output2 shouldBe a[LogBased]
              val output2Config = output2.asInstanceOf[LogBased].config
              output2Config.loggerName should be(RorAuditLoggerName("readonlyrest_audit"))
              output2Config.serializer
                .asInstanceOf[AuditSerializer.Delegating]
                .serializer shouldBe a[QueryAuditLogSerializer]

              val output3 = auditOutputs.toList(2)
              output3 shouldBe a[EsDataStreamBased]
              val output3Config = output3.asInstanceOf[EsDataStreamBased].config
              output3Config.rorAuditDataStream.dataStream should be(fullDataStreamName("readonlyrest_audit"))
              output3Config.serializer
                .asInstanceOf[AuditSerializer.Delegating]
                .serializer shouldBe a[BlockVerbosityAwareAuditLogSerializer]
              output3Config.auditCluster shouldBe AuditCluster.LocalAuditCluster
          }
        }
        "one of outputs is disabled" in {
          val settings = rorSettingsWithAuditUnsafe(
            """
              |  audit:
              |    enabled: true
              |    outputs:
              |    - type: index
              |      enabled: false
              |    - type: log
              |      serializer: "tech.beshu.ror.audit.instances.QueryAuditLogSerializer"
            """.stripMargin
          )

          val core = factory()
            .createCoreFrom(
              settings,
              RorSettingsIndex(IndexName.Full(".readonlyrest")),
              MockHttpClientsFactory,
              MockLdapConnectionPoolProvider,
              NoOpMocksProvider,
              MockedCapabilities.indexOrDataStream
            )
            .map(_.map(_.core))
            .runSyncUnsafe()
          inside(core) {
            case Right(Core(_, RorDependencies(_, _, _), AuditingConfig(Configured(auditOutputs), _, _))) =>
              auditOutputs.size should be(1)

              val output = auditOutputs.head
              output shouldBe a[LogBased]
              val output2Config = output.asInstanceOf[LogBased].config
              output2Config.loggerName should be(RorAuditLoggerName("readonlyrest_audit"))
              output2Config.serializer
                .asInstanceOf[AuditSerializer.Delegating]
                .serializer shouldBe a[QueryAuditLogSerializer]
          }
        }
        "all outputs are disabled" in {
          val settings = rorSettingsWithAuditUnsafe(
            """
              |  audit:
              |    enabled: true
              |    outputs:
              |    - type: index
              |      enabled: false
              |    - type: log
              |      enabled: false
              |    - type: data_stream
              |      enabled: false
            """.stripMargin
          )

          assertOutputsDisabled(settings)
        }
        "default_acl_log_enabled is true by default" should {
          "produce defaultAclLog=true when audit is enabled with outputs and no explicit default_acl_log_enabled" in {
            val settings = rorSettingsWithAuditUnsafe(
              """
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: index
              """.stripMargin
            )

            val core = factory()
              .createCoreFrom(
                settings,
                RorSettingsIndex(IndexName.Full(".readonlyrest")),
                MockHttpClientsFactory,
                MockLdapConnectionPoolProvider,
                NoOpMocksProvider,
                MockedCapabilities.indexOrDataStream
              )
              .map(_.map(_.core))
              .runSyncUnsafe()
            inside(core) {
              case Right(Core(_, RorDependencies(_, _, _), AuditingConfig(Configured(auditOutputs), true, _))) =>
                auditOutputs.size should be(1)
                auditOutputs.head shouldBe a[EsIndexBased]
            }
          }
          "produce defaultAclLog=true when audit is disabled and no explicit default_acl_log_enabled" in {
            val settings = rorSettingsWithAuditUnsafe(
              """
                |  audit:
                |    enabled: false
              """.stripMargin
            )

            val core = factory()
              .createCoreFrom(
                settings,
                RorSettingsIndex(IndexName.Full(".readonlyrest")),
                MockHttpClientsFactory,
                MockLdapConnectionPoolProvider,
                NoOpMocksProvider,
                MockedCapabilities.indexOrDataStream
              )
              .map(_.map(_.core))
              .runSyncUnsafe()
            inside(core) {
              case Right(Core(_, RorDependencies(_, _, _), AuditingConfig(AuditOutputs.Disabled, true, _))) =>
            }
          }
        }
        "default_acl_log_enabled is set to false" should {
          "suppress default ACL log injection when outputs are configured" in {
            val settings = rorSettingsWithAuditUnsafe(
              """
                |  audit:
                |    enabled: true
                |    default_acl_log_enabled: false
                |    outputs:
                |    - type: index
              """.stripMargin
            )

            val core = factory()
              .createCoreFrom(
                settings,
                RorSettingsIndex(IndexName.Full(".readonlyrest")),
                MockHttpClientsFactory,
                MockLdapConnectionPoolProvider,
                NoOpMocksProvider,
                MockedCapabilities.indexOrDataStream
              )
              .map(_.map(_.core))
              .runSyncUnsafe()
            inside(core) {
              case Right(Core(_, RorDependencies(_, _, _), AuditingConfig(Configured(auditOutputs), false, _))) =>
                auditOutputs.size should be(1)
                auditOutputs.head shouldBe a[EsIndexBased]
            }
          }
          "suppress default ACL log injection when no outputs are configured" in {
            val settings = rorSettingsWithAuditUnsafe(
              """
                |  audit:
                |    enabled: true
                |    default_acl_log_enabled: false
              """.stripMargin
            )

            val core = factory()
              .createCoreFrom(
                settings,
                RorSettingsIndex(IndexName.Full(".readonlyrest")),
                MockHttpClientsFactory,
                MockLdapConnectionPoolProvider,
                NoOpMocksProvider,
                MockedCapabilities.indexOrDataStream
              )
              .map(_.map(_.core))
              .runSyncUnsafe()
            inside(core) {
              case Right(
                    Core(
                      _,
                      RorDependencies(_, _, _),
                      AuditingConfig(AuditOutputs.Defaults, false, _)
                    )
                  ) =>
            }
          }
          "work with flat dot-notation key" in {
            val settings = rorSettingsWithAuditUnsafe(
              "audit.default_acl_log_enabled: false"
            )

            val core = factory()
              .createCoreFrom(
                settings,
                RorSettingsIndex(IndexName.Full(".readonlyrest")),
                MockHttpClientsFactory,
                MockLdapConnectionPoolProvider,
                NoOpMocksProvider,
                MockedCapabilities.indexOrDataStream
              )
              .map(_.map(_.core))
              .runSyncUnsafe()
            inside(core) {
              case Right(Core(_, RorDependencies(_, _, _), AuditingConfig(AuditOutputs.Disabled, false, _))) =>
            }
          }
          "work regardless of audit enabled flag" in {
            val settings = rorSettingsWithAuditUnsafe(
              """
                |  audit:
                |    enabled: false
                |    default_acl_log_enabled: false
              """.stripMargin
            )

            val core = factory()
              .createCoreFrom(
                settings,
                RorSettingsIndex(IndexName.Full(".readonlyrest")),
                MockHttpClientsFactory,
                MockLdapConnectionPoolProvider,
                NoOpMocksProvider,
                MockedCapabilities.indexOrDataStream
              )
              .map(_.map(_.core))
              .runSyncUnsafe()
            inside(core) {
              case Right(Core(_, RorDependencies(_, _, _), AuditingConfig(AuditOutputs.Disabled, false, _))) =>
            }
          }
          "reject duplicate default_acl_log_enabled key" in {
            val settings = rorSettingsWithAuditUnsafe(
              """
                |  audit:
                |    enabled: true
                |    default_acl_log_enabled: false
                |  audit.default_acl_log_enabled: false
              """.stripMargin
            )

            assertInvalidSettings(
              settings,
              expectedErrorMessage =
                "Duplicated audit 'default_acl_log_enabled' setting: use either the nested form 'audit: {default_acl_log_enabled: ...}' or the flat form 'audit.default_acl_log_enabled', not both"
            )
          }
        }
        "not be able to be loaded from settings" when {
          "'log' output type" when {
            "not supported custom serializer is set" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: log
                  |      serializer: "tech.beshu.ror.accesscontrol.blocks.RuleOrdering"
                """.stripMargin
              )

              assertInvalidSettings(
                settings,
                expectedErrorMessage =
                  "Class tech.beshu.ror.accesscontrol.blocks.RuleOrdering is not a subclass of tech.beshu.ror.audit.AuditLogSerializer or tech.beshu.ror.requestcontext.AuditLogSerializer"
              )
            }
            "logger name is empty" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: log
                  |      logger_name: ""
                """.stripMargin
              )

              assertInvalidSettings(
                settings,
                expectedErrorMessage = "The audit 'logger_name' cannot be empty"
              )
            }
          }
          "'index' output type" when {
            "not supported custom serializer is set" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: index
                  |      serializer: "tech.beshu.ror.accesscontrol.blocks.RuleOrdering"
                """.stripMargin
              )

              assertInvalidSettings(
                settings,
                expectedErrorMessage =
                  "Class tech.beshu.ror.accesscontrol.blocks.RuleOrdering is not a subclass of tech.beshu.ror.audit.AuditLogSerializer or tech.beshu.ror.requestcontext.AuditLogSerializer"
              )
            }
            "custom audit index name pattern is invalid" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: index
                  |      index_template: "invalid pattern"
                """.stripMargin
              )

              assertInvalidSettings(
                settings,
                expectedErrorMessage =
                  "Illegal pattern specified for audit index template. Have you misplaced quotes? See https://docs.oracle.com/javase/8/docs/api/java/time/format/DateTimeFormatter.html to learn the syntax. Pattern was: invalid pattern error: Unknown pattern letter: i"
              )
            }
            "remote cluster is empty list (array syntax)" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: index
                  |      cluster: []
                """.stripMargin
              )

              assertInvalidSettings(
                settings,
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
                val settings = rorSettingsWithAuditUnsafe(
                  s"""
                     |  audit:
                     |    enabled: true
                     |    outputs:
                     |    - type: index
                     |      cluster:
                     |        nodes: ${auditNodes.map(n => s"\"$n\"").mkString("[", ",", "]")}
                     |        mode: round-robin
                   """.stripMargin
                )

                assertInvalidSettings(
                  settings,
                  expectedErrorMessage =
                    s"One or more audit cluster nodes have inconsistent credentials. Please configure the same credentials. Nodes: ${auditNodes.mkString(", ")}"
                )
              }

              tests.foreach { auditNodes =>
                val settings = rorSettingsWithAuditUnsafe(
                  s"""
                     |  audit:
                     |    enabled: true
                     |    outputs:
                     |    - type: index
                     |      cluster: ${auditNodes.map(n => s"\"$n\"").mkString("[", ",", "]")}
                   """.stripMargin
                )

                assertInvalidSettings(
                  settings,
                  expectedErrorMessage =
                    s"One or more audit cluster nodes have inconsistent credentials. Please configure the same credentials. Nodes: ${auditNodes.mkString(", ")}"
                )
              }
            }

            "remote cluster is empty list (extended syntax)" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: index
                  |      cluster:
                  |        nodes: []
                  |        mode: round-robin
                """.stripMargin
              )

              assertInvalidSettings(
                settings,
                expectedErrorMessage = "Error for field 'nodes': Non empty list of valid URI is required"
              )
            }
            "remote cluster has invalid mode" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: index
                  |      cluster:
                  |        nodes: ["1.1.1.1"]
                  |        mode: not-existing-mode
                """.stripMargin
              )

              assertInvalidSettings(
                settings,
                expectedErrorMessage =
                  "Error for field 'mode': Unknown cluster mode [not-existing-mode], allowed values are: [round-robin]"
              )
            }
            "remote cluster credentials malformed" when {
              "password not provided" in {
                val settings = rorSettingsWithAuditUnsafe(
                  """
                    |  audit:
                    |    enabled: true
                    |    outputs:
                    |    - type: index
                    |      cluster:
                    |        nodes: ["1.1.1.1"]
                    |        mode: round-robin
                    |        username: user
                  """.stripMargin
                )

                assertInvalidSettings(
                  settings,
                  expectedErrorMessage = "Audit output configuration is missing the 'password' field."
                )
              }
              "username not provided" in {
                val settings = rorSettingsWithAuditUnsafe(
                  """
                    |  audit:
                    |    enabled: true
                    |    outputs:
                    |    - type: index
                    |      cluster:
                    |        nodes: ["1.1.1.1"]
                    |        mode: round-robin
                    |        password: pass
                  """.stripMargin
                )

                assertInvalidSettings(
                  settings,
                  expectedErrorMessage = "Audit output configuration is missing the 'username' field."
                )
              }
            }
          }
          "'data_stream' output type" when {
            "not supported custom serializer is set" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: data_stream
                  |      serializer: "tech.beshu.ror.accesscontrol.blocks.RuleOrdering"
                """.stripMargin
              )

              assertInvalidSettings(
                settings,
                expectedErrorMessage =
                  "Class tech.beshu.ror.accesscontrol.blocks.RuleOrdering is not a subclass of tech.beshu.ror.audit.AuditLogSerializer or tech.beshu.ror.requestcontext.AuditLogSerializer"
              )
            }
            "data stream name is invalid" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: data_stream
                  |      data_stream: ".ds-INVALID-data-stream-name#"
                """.stripMargin
              )

              assertInvalidSettings(
                settings,
                expectedErrorMessage =
                  "Error for field 'data_stream': Illegal format for ROR audit 'data_stream' name - Data stream '.ds-INVALID-data-stream-name#' has an invalid format. Cause: " +
                    "name must be lowercase, " +
                    "name must not contain forbidden characters '\\', '/', '*', '?', '\"', '<', '>', '|', ',', '#', ':', ' ', " +
                    "name must not start with '-', '_', '+', '.ds-'."
              )
            }
            "remote cluster is empty list" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: data_stream
                  |      cluster: []
                """.stripMargin
              )

              assertInvalidSettings(
                settings,
                expectedErrorMessage = "Error for field 'cluster': Non empty list of valid URI is required"
              )
            }
            "the ES module does not support data streams" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    enabled: true
                  |    outputs:
                  |    - type: data_stream
                """.stripMargin
              )

              assertInvalidSettings(
                settings,
                expectedErrorMessage =
                  "Error for field 'type': Data stream audit output is supported from Elasticsearch version 7.9.0. Use 'index' type or upgrade to 7.9.0 or later.",
                auditCapabilities = IndexOnly(MockIndexBasedAuditOutputServiceCreator)
              )
            }
          }
          "unknown output type is set" in {
            val settings = rorSettingsWithAuditUnsafe(
              """
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: custom_type
              """.stripMargin
            )

            assertInvalidSettings(
              settings,
              expectedErrorMessage =
                "Error for field 'type': Unsupported type of audit output: custom_type. Supported types: [data_stream, index, log]"
            )
          }
          "unknown output type is set when using simple format" in {
            val settings = rorSettingsWithAuditUnsafe(
              """
                |  audit:
                |    enabled: true
                |    outputs: [ custom_type ]
              """.stripMargin
            )

            assertInvalidSettings(
              settings,
              expectedErrorMessage =
                "Unsupported type of audit output: custom_type. Supported types: [data_stream, index, log]",
              auditCapabilities = MockedCapabilities.indexOrDataStream
            )

            assertInvalidSettings(
              settings,
              expectedErrorMessage = "Unsupported type of audit output: custom_type. Supported types: [index, log]",
              auditCapabilities = MockedCapabilities.indexOnly
            )
          }
          "'outputs' array is empty" in {
            val settings = rorSettingsWithAuditUnsafe(
              """
                |  audit:
                |    enabled: true
                |    outputs: []
              """.stripMargin
            )

            assertInvalidSettings(
              settings,
              expectedErrorMessage = "The audit 'outputs' array cannot be empty"
            )
          }
          "configurable serializer is set with invalid value descriptor" in {
            val settings = rorSettingsWithAuditUnsafe(
              """
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
              """.stripMargin
            )

            assertInvalidSettings(
              settings,
              expectedErrorMessage =
                "Configurable serializer is used, but the 'fields' setting is missing or invalid: There are invalid placeholder values: HTTP_METHOD2"
            )
          }
          "configurable serializer is set, but without fields setting" in {
            val settings = rorSettingsWithAuditUnsafe(
              """
                |  audit:
                |    enabled: true
                |    outputs:
                |    - type: log
                |      serializer:
                |        type: configurable
                |        verbosity_level_serialization_mode: [INFO]
              """.stripMargin
            )

            assertInvalidSettings(
              settings,
              expectedErrorMessage =
                "Configurable serializer is used, but the 'fields' setting is missing or invalid: Missing required field"
            )
          }
        }
      }
      "deprecated format is used" should {
        "ignore deprecated fields when both formats are used at once" in {
          val settings = rorSettingsWithAuditUnsafe(
            """
              |  audit:
              |    enabled: true
              |    outputs:
              |    - type: index
              |    # deprecated fields
              |    collector: false
              |    serializer: "tech.beshu.ror.audit.instances.QueryAuditLogSerializer"
            """.stripMargin
          )

          assertIndexBasedAuditOutputConfigPresent[BlockVerbosityAwareAuditLogSerializer](
            settings,
            expectedIndexName = "readonlyrest_audit-2018-12-31",
            expectedAuditCluster = LocalAuditCluster
          )
        }
        "be optional" when {
          "audit collector is disabled" when {
            "'audit' section is defined" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    collector: false
                """.stripMargin
              )

              assertOutputsDisabled(settings)
            }
            "'audit' section is not defined" in {
              val settings = rorSettingsWithAuditUnsafe(
                "audit_collector: false"
              )

              assertOutputsDisabled(settings)
            }
          }
        }
        "be able to be loaded from settings" when {
          "'audit' section is defined" when {
            "audit collector is enabled" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    collector: true
                """.stripMargin
              )

              assertIndexBasedAuditOutputConfigPresent[BlockVerbosityAwareAuditLogSerializer](
                settings,
                expectedIndexName = "readonlyrest_audit-2018-12-31",
                expectedAuditCluster = LocalAuditCluster
              )
            }
            "custom audit index name is set" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    collector: true
                  |    index_template: "'custom_template_'yyyyMMdd"
                """.stripMargin
              )

              assertIndexBasedAuditOutputConfigPresent[BlockVerbosityAwareAuditLogSerializer](
                settings,
                expectedIndexName = "custom_template_20181231",
                expectedAuditCluster = LocalAuditCluster
              )
            }
            "custom serializer is set" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    collector: true
                  |    serializer: "tech.beshu.ror.audit.instances.QueryAuditLogSerializer"
                """.stripMargin
              )

              assertIndexBasedAuditOutputConfigPresent[QueryAuditLogSerializer](
                settings,
                expectedIndexName = "readonlyrest_audit-2018-12-31",
                expectedAuditCluster = LocalAuditCluster
              )
            }
            "deprecated custom serializer is set" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    collector: true
                  |    serializer: "tech.beshu.ror.requestcontext.QueryAuditLogSerializer"
                """.stripMargin
              )

              assertIndexBasedAuditOutputConfigPresent[DeprecatedAuditLogSerializerAdapter[_]](
                settings,
                expectedIndexName = "readonlyrest_audit-2018-12-31",
                expectedAuditCluster = LocalAuditCluster
              )
            }
            "custom audit cluster is set" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    collector: true
                  |    cluster: ["1.1.1.1"]
                """.stripMargin
              )

              assertIndexBasedAuditOutputConfigPresent[BlockVerbosityAwareAuditLogSerializer](
                settings,
                expectedIndexName = "readonlyrest_audit-2018-12-31",
                expectedAuditCluster = RemoteAuditCluster(
                  nodes = UniqueNonEmptyList.of(AuditClusterNode(Uri.parse("1.1.1.1"))),
                  mode = ClusterMode.RoundRobin,
                  credentials = None
                )
              )
            }
            "all audit settings are custom" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    collector: true
                  |    index_template: "'custom_template_'yyyyMMdd"
                  |    serializer: "tech.beshu.ror.audit.instances.QueryAuditLogSerializer"
                  |    cluster: ["1.1.1.1"]
                """.stripMargin
              )

              assertIndexBasedAuditOutputConfigPresent[QueryAuditLogSerializer](
                settings,
                expectedIndexName = "custom_template_20181231",
                expectedAuditCluster = RemoteAuditCluster(
                  nodes = UniqueNonEmptyList.of(AuditClusterNode(Uri.parse("1.1.1.1"))),
                  mode = ClusterMode.RoundRobin,
                  credentials = None
                )
              )
            }
          }
          "'audit' section is not defined" when {
            "audit collector is enabled" in {
              val settings = rorSettingsWithAuditUnsafe(
                "audit_collector: true"
              )

              assertIndexBasedAuditOutputConfigPresent[BlockVerbosityAwareAuditLogSerializer](
                settings,
                expectedIndexName = "readonlyrest_audit-2018-12-31",
                expectedAuditCluster = LocalAuditCluster
              )
            }
            "custom audit index name is set" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit_collector: true
                  |  audit_index_template: "'custom_template_'yyyyMMdd"
                """.stripMargin
              )

              assertIndexBasedAuditOutputConfigPresent[BlockVerbosityAwareAuditLogSerializer](
                settings,
                expectedIndexName = "custom_template_20181231",
                expectedAuditCluster = LocalAuditCluster
              )
            }
            "custom serializer is set" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit_collector: true
                  |  audit_serializer: "tech.beshu.ror.audit.instances.QueryAuditLogSerializer"
                """.stripMargin
              )

              assertIndexBasedAuditOutputConfigPresent[QueryAuditLogSerializer](
                settings,
                expectedIndexName = "readonlyrest_audit-2018-12-31",
                expectedAuditCluster = LocalAuditCluster
              )
            }
            "deprecated custom serializer is set" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit_collector: true
                  |  audit_serializer: "tech.beshu.ror.requestcontext.QueryAuditLogSerializer"
                """.stripMargin
              )

              assertIndexBasedAuditOutputConfigPresent[DeprecatedAuditLogSerializerAdapter[_]](
                settings,
                expectedIndexName = "readonlyrest_audit-2018-12-31",
                expectedAuditCluster = LocalAuditCluster
              )
            }
            "custom audit cluster is set" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit_collector: true
                  |  audit_cluster: ["http://user:test@1.1.1.1"]
                """.stripMargin
              )

              assertIndexBasedAuditOutputConfigPresent[BlockVerbosityAwareAuditLogSerializer](
                settings,
                expectedIndexName = "readonlyrest_audit-2018-12-31",
                expectedAuditCluster = RemoteAuditCluster(
                  nodes = UniqueNonEmptyList.of(AuditClusterNode(Uri.parse("http://user:test@1.1.1.1"))),
                  mode = ClusterMode.RoundRobin,
                  credentials = Some(NodeCredentials("user", "test"))
                )
              )
            }
            "all audit settings are custom" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit_collector: true
                  |  audit_index_template: "'custom_template_'yyyyMMdd"
                  |  audit_serializer: "tech.beshu.ror.audit.instances.QueryAuditLogSerializer"
                  |  audit_cluster: ["1.1.1.1"]
                """.stripMargin
              )

              assertIndexBasedAuditOutputConfigPresent[QueryAuditLogSerializer](
                settings,
                expectedIndexName = "custom_template_20181231",
                expectedAuditCluster = RemoteAuditCluster(
                  nodes = UniqueNonEmptyList.of(AuditClusterNode(Uri.parse("1.1.1.1"))),
                  mode = ClusterMode.RoundRobin,
                  credentials = None
                )
              )
            }

          }
        }
        "not be able to be loaded from settings" when {
          "'audit' section is defined" when {
            "not supported custom serializer is set" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    collector: true
                  |    serializer: "tech.beshu.ror.accesscontrol.blocks.RuleOrdering"
                """.stripMargin
              )

              assertInvalidSettings(
                settings,
                expectedErrorMessage =
                  "Class tech.beshu.ror.accesscontrol.blocks.RuleOrdering is not a subclass of tech.beshu.ror.audit.AuditLogSerializer or tech.beshu.ror.requestcontext.AuditLogSerializer"
              )
            }
            "custom audit index name pattern is invalid" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    collector: true
                  |    index_template: "invalid pattern"
                """.stripMargin
              )

              assertInvalidSettings(
                settings,
                expectedErrorMessage =
                  "Error for field 'index_template': Illegal pattern specified for audit index template. Have you misplaced quotes? See https://docs.oracle.com/javase/8/docs/api/java/time/format/DateTimeFormatter.html to learn the syntax. Pattern was: invalid pattern error: Unknown pattern letter: i"
              )
            }
            "remote cluster is empty list" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit:
                  |    collector: true
                  |    cluster: []
                """.stripMargin
              )

              assertInvalidSettings(
                settings,
                expectedErrorMessage = "Error for field 'cluster': Non empty list of valid URI is required"
              )
            }
          }
          "'audit' section is not defined" when {
            "not supported custom serializer is set" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit_collector: true
                  |  audit_serializer: "tech.beshu.ror.accesscontrol.blocks.RuleOrdering"
                """.stripMargin
              )

              assertInvalidSettings(
                settings,
                expectedErrorMessage =
                  "Class tech.beshu.ror.accesscontrol.blocks.RuleOrdering is not a subclass of tech.beshu.ror.audit.AuditLogSerializer or tech.beshu.ror.requestcontext.AuditLogSerializer"
              )
            }
            "custom audit index name pattern is invalid" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit_collector: true
                  |  audit_index_template: "invalid pattern"
                """.stripMargin
              )

              assertInvalidSettings(
                settings,
                expectedErrorMessage =
                  "Error for field 'audit_index_template': Illegal pattern specified for audit index template. Have you misplaced quotes? See https://docs.oracle.com/javase/8/docs/api/java/time/format/DateTimeFormatter.html to learn the syntax. Pattern was: invalid pattern error: Unknown pattern letter: i"
              )
            }
            "remote cluster is empty list" in {
              val settings = rorSettingsWithAuditUnsafe(
                """
                  |  audit_collector: true
                  |  audit_cluster: []
                """.stripMargin
              )

              assertInvalidSettings(
                settings,
                expectedErrorMessage = "Error for field 'audit_cluster': Non empty list of valid URI is required"
              )
            }
          }
        }
      }
    }
  }

  private def factory() = {
    implicit val systemContext: SystemContext = SystemContext.default
    val esEnv = EsEnv(File("/config"), File("/modules"), defaultEsVersionForTests, defaultTestEsNodeSettings)
    new RawRorSettingsBasedCoreFactory(esEnv)
  }

  private def rorSettingsWithAuditUnsafe(auditSection: String) = {
    val rawSettings =
      s"""
         |readonlyrest:
         |  $auditSection
         |
         |  access_control_rules:
         |
         |  - name: test_block
         |    type: allow
         |    auth_key: admin:container
       """.stripMargin
    rorSettingsFromUnsafe(rawSettings)
  }

  private def assertOutputsDisabled(settings: RawRorSettings): Unit = {
    val core = factory()
      .createCoreFrom(
        settings,
        RorSettingsIndex(IndexName.Full(".readonlyrest")),
        MockHttpClientsFactory,
        MockLdapConnectionPoolProvider,
        NoOpMocksProvider,
        MockedCapabilities.indexOrDataStream
      )
      .map(_.map(_.core))
      .runSyncUnsafe()
    inside(core) { case Right(Core(_, RorDependencies(_, _, _), AuditingConfig(AuditOutputs.Disabled, _, _))) =>
    }
  }

  private def assertDefaultOutputs(settings: RawRorSettings): Unit = {
    val core = factory()
      .createCoreFrom(
        settings,
        RorSettingsIndex(IndexName.Full(".readonlyrest")),
        MockHttpClientsFactory,
        MockLdapConnectionPoolProvider,
        NoOpMocksProvider,
        MockedCapabilities.indexOrDataStream
      )
      .map(_.map(_.core))
      .runSyncUnsafe()
    inside(core) {
      case Right(
            Core(_, RorDependencies(_, _, _), AuditingConfig(AuditOutputs.Defaults, _, _))
          ) =>
    }
  }

  private def assertIndexBasedAuditOutputConfigPresent[EXPECTED_SERIALIZER: ClassTag](
      settings: RawRorSettings,
      expectedIndexName: NonEmptyString,
      expectedAuditCluster: AuditCluster
  ) = {
    doAssertIndexBasedAuditOutputConfigPresent(
      settings,
      expectedIndexName,
      expectedAuditCluster,
      _.asInstanceOf[AuditSerializer.Delegating].serializer shouldBe a[EXPECTED_SERIALIZER],
    )
  }

  private def assertIndexBasedEcsAuditOutputConfigPresent(
      settings: RawRorSettings,
      expectedIndexName: NonEmptyString,
      expectedAuditCluster: AuditCluster
  ) = {
    doAssertIndexBasedAuditOutputConfigPresent(
      settings,
      expectedIndexName,
      expectedAuditCluster,
      _ shouldBe a[AuditSerializer.EcsV1],
    )
  }

  private def doAssertIndexBasedAuditOutputConfigPresent(
      settings: RawRorSettings,
      expectedIndexName: NonEmptyString,
      expectedAuditCluster: AuditCluster,
      serializerAssertion: AuditSerializer => Assertion,
  ) = {
    val core = factory()
      .createCoreFrom(
        settings,
        RorSettingsIndex(IndexName.Full(".readonlyrest")),
        MockHttpClientsFactory,
        MockLdapConnectionPoolProvider,
        NoOpMocksProvider,
        MockedCapabilities.indexOrDataStream
      )
      .map(_.map(_.core))
      .runSyncUnsafe()
    inside(core) { case Right(Core(_, RorDependencies(_, _, _), AuditingConfig(Configured(auditOutputs), _, _))) =>
      auditOutputs.size should be(1)

      val headOutput = auditOutputs.head
      headOutput shouldBe a[EsIndexBased]

      val outputConfig = headOutput.asInstanceOf[EsIndexBased].config
      outputConfig.rorAuditIndexTemplate.indexName(zonedDateTime.toInstant) should be(indexName(expectedIndexName))
      serializerAssertion(outputConfig.serializer)
      outputConfig.auditCluster shouldBe expectedAuditCluster
    }
  }

  private def assertDataStreamAuditOutputConfigPresent[EXPECTED_SERIALIZER: ClassTag](
      settings: RawRorSettings,
      expectedDataStreamName: NonEmptyString,
      expectedAuditCluster: AuditCluster,
  ) = {
    doAssertDataStreamAuditOutputConfigPresent(
      settings,
      expectedDataStreamName,
      expectedAuditCluster,
      _.asInstanceOf[AuditSerializer.Delegating].serializer shouldBe a[EXPECTED_SERIALIZER],
    )
  }

  private def doAssertDataStreamAuditOutputConfigPresent(
      settings: RawRorSettings,
      expectedDataStreamName: NonEmptyString,
      expectedAuditCluster: AuditCluster,
      serializerAssertion: AuditSerializer => Assertion,
  ) = {
    val core = factory()
      .createCoreFrom(
        settings,
        RorSettingsIndex(IndexName.Full(".readonlyrest")),
        MockHttpClientsFactory,
        MockLdapConnectionPoolProvider,
        NoOpMocksProvider,
        MockedCapabilities.indexOrDataStream
      )
      .map(_.map(_.core))
      .runSyncUnsafe()
    inside(core) { case Right(Core(_, RorDependencies(_, _, _), AuditingConfig(Configured(auditOutputs), _, _))) =>
      auditOutputs.size should be(1)

      val headOutput = auditOutputs.head
      headOutput shouldBe a[EsDataStreamBased]

      val outputConfig = headOutput.asInstanceOf[EsDataStreamBased].config
      outputConfig.rorAuditDataStream.dataStream should be(fullDataStreamName(expectedDataStreamName))
      serializerAssertion(outputConfig.serializer)
      outputConfig.auditCluster shouldBe expectedAuditCluster
    }
  }

  private def auditLogSerializer(
      settings: RawRorSettings,
  ): AuditLogSerializer = serializer(settings) match {
    case AuditSerializer.Delegating(s) => s
    case _ => throw new IllegalStateException("Expected delegating serializer for rolling file output")
  }

  private def ecsSerializer(
      settings: RawRorSettings,
  ): AuditSerializer.EcsV1 = serializer(settings) match {
    case s: AuditSerializer.EcsV1 => s
    case _ => throw new IllegalStateException("Expected delegating serializer for rolling file output")
  }

  private def serializer(
      settings: RawRorSettings,
  ): AuditSerializer = {
    val core = factory()
      .createCoreFrom(
        settings,
        RorSettingsIndex(IndexName.Full(".readonlyrest")),
        MockHttpClientsFactory,
        MockLdapConnectionPoolProvider,
        NoOpMocksProvider,
        MockedCapabilities.indexOrDataStream
      )
      .map(_.map(_.core))
      .runSyncUnsafe()

    core match {
      case Right(Core(_, _, AuditingConfig(Configured(auditOutputs), _, _))) =>
        val headOutput = auditOutputs.head
        headOutput match {
          case c: EsIndexBased      => c.config.serializer
          case c: EsDataStreamBased => c.config.serializer
          case c: LogBased          => c.config.serializer
          case c: RollingFileBased  => c.config.serializer
        }
      case _ =>
        throw new IllegalStateException("Expected auditing config is not present")
    }
  }

  private def assertLogBasedAuditOutputConfigPresent[EXPECTED_SERIALIZER: ClassTag](
      settings: RawRorSettings,
      expectedLoggerName: NonEmptyString
  ) = {
    val core = factory()
      .createCoreFrom(
        settings,
        RorSettingsIndex(IndexName.Full(".readonlyrest")),
        MockHttpClientsFactory,
        MockLdapConnectionPoolProvider,
        NoOpMocksProvider,
        MockedCapabilities.indexOrDataStream
      )
      .map(_.map(_.core))
      .runSyncUnsafe()
    inside(core) { case Right(Core(_, RorDependencies(_, _, _), AuditingConfig(Configured(auditOutputs), _, _))) =>
      auditOutputs.size should be(1)

      val headOutput = auditOutputs.head
      headOutput shouldBe a[LogBased]

      val outputConfig = headOutput.asInstanceOf[LogBased].config
      outputConfig.loggerName should be(RorAuditLoggerName(expectedLoggerName))
      outputConfig.serializer match {
        case AuditSerializer.Delegating(s) => s shouldBe a[EXPECTED_SERIALIZER]
        case s                             => s shouldBe a[EXPECTED_SERIALIZER]
      }
    }
  }

  private def assertLogBasedAuditOutputFileConfigPresent(
      settings: RawRorSettings,
      expectedLoggerName: NonEmptyString,
      expectedFileAppender: RollingFileBased.FileAppender
  ) = {
    val core = factory()
      .createCoreFrom(
        settings,
        RorSettingsIndex(IndexName.Full(".readonlyrest")),
        MockHttpClientsFactory,
        MockLdapConnectionPoolProvider,
        NoOpMocksProvider,
        MockedCapabilities.indexOrDataStream
      )
      .map(_.map(_.core))
      .runSyncUnsafe()
    inside(core) { case Right(Core(_, RorDependencies(_, _, _), AuditingConfig(Configured(auditOutputs), _, _))) =>
      auditOutputs.size should be(1)

      val headOutput = auditOutputs.head
      headOutput shouldBe a[RollingFileBased]

      val outputConfig = headOutput.asInstanceOf[RollingFileBased].config
      outputConfig.loggerName should be(RorAuditLoggerName(expectedLoggerName))
      outputConfig.fileAppender should be(expectedFileAppender)
    }
  }

  private def assertInvalidSettings(
      settings: RawRorSettings,
      expectedErrorMessage: String,
      auditCapabilities: EsAuditCapabilities = MockedCapabilities.indexOrDataStream
  ): Unit = {
    val core = factory()
      .createCoreFrom(
        settings,
        RorSettingsIndex(IndexName.Full(".readonlyrest")),
        MockHttpClientsFactory,
        MockLdapConnectionPoolProvider,
        NoOpMocksProvider,
        auditCapabilities
      )
      .map(_.map(_.core))
      .runSyncUnsafe()
    inside(core) { case Left(errors) =>
      errors.length should be(1)
      errors.head should be(AuditingSettingsCreationError(Message(expectedErrorMessage)))
    }
  }

  private def circeJsonWithIgnoredTimestamp(json: JSONObject): Option[Json] = {
    json
      .withTimestampValue("IGNORED")
      .withEventDurationValue("IGNORED")
      .circeJsonE
      .toOption
  }

  extension (jsonObject: JSONObject) {

    private def withTimestampValue(value: String): JSONObject = {
      jsonObject.put("@timestamp", value)
    }

    // `AuditResponseContext.duration` is `now - requestContext.timestamp`, and the two clock reads
    // happen at different moments, so the value is 5000ms only when nothing elapses between them.
    // Under CI load it becomes 5001 and the whole-document comparison fails (seen on
    // integration_es78x). Normalise it on BOTH sides, exactly like @timestamp.
    private def withEventDurationValue(value: String): JSONObject = {
      if (jsonObject.has("event") && jsonObject.getJSONObject("event").has("duration")) {
        jsonObject.getJSONObject("event").put("duration", value)
      }
      jsonObject
    }

    private def circeJsonE: Either[String, Json] =
      parser.parse(jsonObject.toString(0)).left.map(_.getMessage)
  }

}

private class TestEnvironmentAwareAuditLogSerializer extends EnvironmentAwareAuditLogSerializer {

  def onResponse(
      responseContext: AuditResponseContext,
      environmentContext: AuditEnvironmentContext
  ): Option[JSONObject] = Some(
    new JSONObject()
      .put("custom_field_for_es_node_name", environmentContext.esNodeName)
      .put("custom_field_for_es_cluster_name", environmentContext.esClusterName)
  )

}

private class DummyAuditRequestContext(
    override val loggedInUserName: Option[String] = Some("logged_user"),
    override val attemptedUserName: Option[String] = Some("basic auth user")
) extends AuditRequestContext {
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

  override def auditEnvironmentContext: AuditEnvironmentContext = new AuditEnvironmentContextBasedOnEsNodeSettings(
    defaultTestEsNodeSettings
  )

  override def matchedBlockNames: Option[List[String]] = Some(List("block1", "block2"))
}
