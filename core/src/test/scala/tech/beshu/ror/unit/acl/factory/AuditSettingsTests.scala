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
import eu.timepit.refined.auto.*
import eu.timepit.refined.types.string.NonEmptyString
import io.lemonlabs.uri.Uri
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.audit.AuditingTool.Settings.AuditSink
import tech.beshu.ror.accesscontrol.audit.AuditingTool.Settings.AuditSink.Config
import tech.beshu.ror.accesscontrol.blocks.mocks.NoOpMocksProvider
import tech.beshu.ror.accesscontrol.domain.AuditCluster.{LocalAuditCluster, RemoteAuditCluster}
import tech.beshu.ror.accesscontrol.domain.{AuditCluster, IndexName, RorAuditLoggerName, RorConfigurationIndex}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.AuditingSettingsCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.{Core, RawRorConfigBasedCoreFactory}
import tech.beshu.ror.audit.adapters.DeprecatedAuditLogSerializerAdapter
import tech.beshu.ror.audit.instances.{DefaultAuditLogSerializer, QueryAuditLogSerializer}
import tech.beshu.ror.configuration.{EnvironmentConfig, RawRorConfig, RorConfig}
import tech.beshu.ror.mocks.{MockHttpClientsFactory, MockLdapConnectionPoolProvider}
import tech.beshu.ror.utils.TestsUtils.*

import java.time.{ZoneId, ZonedDateTime}
import scala.reflect.ClassTag

class AuditSettingsTests extends AnyWordSpec with Inside {

  private val factory = {
    implicit val environmentConfig: EnvironmentConfig = EnvironmentConfig.default
    new RawRorConfigBasedCoreFactory()
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

              assertIndexBasedAuditSinkSettingsPresent[DefaultAuditLogSerializer](
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

              assertIndexBasedAuditSinkSettingsPresent[DefaultAuditLogSerializer](
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
              |    outputs: [index, log]
              |
              |  access_control_rules:
              |
              |  - name: test_block
              |    type: allow
              |    auth_key: admin:container
              |
            """.stripMargin)

          val core = factory
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
            sink1 shouldBe a[AuditSink.Enabled]
            val enabledSink1 = sink1.asInstanceOf[AuditSink.Enabled].config
            enabledSink1 shouldBe a[Config.EsIndexBasedSink]
            val sink1Config = enabledSink1.asInstanceOf[Config.EsIndexBasedSink]
            sink1Config.rorAuditIndexTemplate.indexName(zonedDateTime.toInstant) should be(indexName("readonlyrest_audit-2018-12-31"))
            sink1Config.logSerializer shouldBe a[DefaultAuditLogSerializer]
            sink1Config.auditCluster shouldBe AuditCluster.LocalAuditCluster

            val sink2 = auditingSettings.auditSinks.toList(1)
            sink2 shouldBe a[AuditSink.Enabled]
            val enabledSink2 = sink2.asInstanceOf[AuditSink.Enabled].config
            enabledSink2 shouldBe a[Config.LogBasedSink]
            val sink2Config = enabledSink2.asInstanceOf[Config.LogBasedSink]
            sink2Config.loggerName should be(RorAuditLoggerName("readonlyrest_audit"))
            sink2Config.logSerializer shouldBe a[DefaultAuditLogSerializer]
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

            assertLogBasedAuditSinkSettingsPresent[DefaultAuditLogSerializer](
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

              assertLogBasedAuditSinkSettingsPresent[DefaultAuditLogSerializer](
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

            assertLogBasedAuditSinkSettingsPresent[DefaultAuditLogSerializer](
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

            assertIndexBasedAuditSinkSettingsPresent[DefaultAuditLogSerializer](
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

              assertIndexBasedAuditSinkSettingsPresent[DefaultAuditLogSerializer](
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

            assertIndexBasedAuditSinkSettingsPresent[DefaultAuditLogSerializer](
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

            assertIndexBasedAuditSinkSettingsPresent[DefaultAuditLogSerializer](
              config,
              expectedIndexName = "readonlyrest_audit-2018-12-31",
              expectedAuditCluster = RemoteAuditCluster(NonEmptyList.one(Uri.parse("1.1.1.1")))
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
              expectedAuditCluster = RemoteAuditCluster(NonEmptyList.one(Uri.parse("1.1.1.1")))
            )
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
              |
              |  access_control_rules:
              |
              |  - name: test_block
              |    type: allow
              |    auth_key: admin:container
              |
            """.stripMargin)

          val core = factory
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
            sink1 shouldBe a[AuditSink.Enabled]
            val enabledSink1 = sink1.asInstanceOf[AuditSink.Enabled].config
            enabledSink1 shouldBe a[Config.EsIndexBasedSink]
            val sink1Config = enabledSink1.asInstanceOf[Config.EsIndexBasedSink]
            sink1Config.rorAuditIndexTemplate.indexName(zonedDateTime.toInstant) should be(indexName("readonlyrest_audit-2018-12-31"))
            sink1Config.logSerializer shouldBe a[DefaultAuditLogSerializer]
            sink1Config.auditCluster shouldBe AuditCluster.LocalAuditCluster

            val sink2 = auditingSettings.auditSinks.toList(1)
            sink2 shouldBe a[AuditSink.Enabled]
            val enabledSink2 = sink2.asInstanceOf[AuditSink.Enabled].config
            enabledSink2 shouldBe a[Config.LogBasedSink]
            val sink2Config = enabledSink2.asInstanceOf[Config.LogBasedSink]
            sink2Config.loggerName should be(RorAuditLoggerName("readonlyrest_audit"))
            sink2Config.logSerializer shouldBe a[QueryAuditLogSerializer]
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

          val core = factory
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
            "remote cluster is empty list" in {
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
              expectedErrorMessage = "Unsupported 'type' of audit output: custom_type. Supported types: [index, log]"
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
              expectedErrorMessage = "Unsupported 'type' of audit output: custom_type. Supported types: [index, log]"
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

          assertIndexBasedAuditSinkSettingsPresent[DefaultAuditLogSerializer](
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

              assertIndexBasedAuditSinkSettingsPresent[DefaultAuditLogSerializer](
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

              assertIndexBasedAuditSinkSettingsPresent[DefaultAuditLogSerializer](
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

              assertIndexBasedAuditSinkSettingsPresent[DefaultAuditLogSerializer](
                config,
                expectedIndexName = "readonlyrest_audit-2018-12-31",
                expectedAuditCluster = RemoteAuditCluster(NonEmptyList.one(Uri.parse("1.1.1.1")))
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
                expectedAuditCluster = RemoteAuditCluster(NonEmptyList.one(Uri.parse("1.1.1.1")))
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

              assertIndexBasedAuditSinkSettingsPresent[DefaultAuditLogSerializer](
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

              assertIndexBasedAuditSinkSettingsPresent[DefaultAuditLogSerializer](
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
                  |  audit_cluster: ["user:test@1.1.1.1"]
                  |
                  |  access_control_rules:
                  |
                  |  - name: test_block
                  |    type: allow
                  |    auth_key: admin:container
                  |
              """.stripMargin)

              assertIndexBasedAuditSinkSettingsPresent[DefaultAuditLogSerializer](
                config,
                expectedIndexName = "readonlyrest_audit-2018-12-31",
                expectedAuditCluster = RemoteAuditCluster(NonEmptyList.one(Uri.parse("user:test@1.1.1.1")))
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
                expectedAuditCluster = RemoteAuditCluster(NonEmptyList.one(Uri.parse("1.1.1.1")))
              )
            }

          }
        }
        "not be able to be loaded from config" when {
          "'audit' section is defined" when {
            "not supported custom serializer is set" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit:
                  |    collector: true
                  |    serializer: "tech.beshu.ror.accesscontrol.blocks.RuleOrdering"
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
                  |    collector: true
                  |    index_template: "invalid pattern"
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
            "remote cluster is empty list" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit:
                  |    collector: true
                  |    cluster: []
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
          }
          "'audit' section is not defined" when {
            "not supported custom serializer is set" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit_collector: true
                  |  audit_serializer: "tech.beshu.ror.accesscontrol.blocks.RuleOrdering"
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
                  |  audit_collector: true
                  |  audit_index_template: "invalid pattern"
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
            "remote cluster is empty list" in {
              val config = rorConfigFromUnsafe(
                """
                  |readonlyrest:
                  |  audit_collector: true
                  |  audit_cluster: []
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
          }
        }
      }
    }
  }

  private def assertSettingsNoPresent(config: RawRorConfig): Unit = {
    val core = factory
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
    val core = factory
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
    val core = factory
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

  private def assertLogBasedAuditSinkSettingsPresent[EXPECTED_SERIALIZER: ClassTag](config: RawRorConfig,
                                                                                    expectedLoggerName: NonEmptyString) = {
    val core = factory
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
                                    expectedErrorMessage: String): Unit = {
    val core = factory
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
}
