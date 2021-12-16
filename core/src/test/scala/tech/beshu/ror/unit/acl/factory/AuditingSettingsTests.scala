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
import com.softwaremill.sttp.Uri
import eu.timepit.refined.auto._
import eu.timepit.refined.types.string.NonEmptyString
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.mocks.NoOpMocksProvider
import tech.beshu.ror.accesscontrol.domain.AuditCluster.{LocalAuditCluster, RemoteAuditCluster}
import tech.beshu.ror.accesscontrol.domain.{AuditCluster, IndexName, RorConfigurationIndex}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.AuditingSettingsCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.{CoreSettings, RawRorConfigBasedCoreFactory}
import tech.beshu.ror.audit.adapters.DeprecatedAuditLogSerializerAdapter
import tech.beshu.ror.audit.instances.{DefaultAuditLogSerializer, QueryAuditLogSerializer}
import tech.beshu.ror.boot.RorMode
import tech.beshu.ror.configuration.RawRorConfig
import tech.beshu.ror.mocks.{MockHttpClientsFactory, MockLdapConnectionPoolProvider}
import tech.beshu.ror.providers._
import tech.beshu.ror.utils.TestsUtils._

import java.time.{Clock, ZoneId, ZonedDateTime}
import scala.reflect.ClassTag

class AuditingSettingsTests extends AnyWordSpec with Inside {

  private val factory = {
    implicit val clock: Clock = Clock.systemUTC()
    implicit val uuidProvider: UuidProvider = JavaUuidProvider
    implicit val envVarsProvider: EnvVarsProvider = OsEnvVarsProvider
    implicit val propertiesProvider: PropertiesProvider = JvmPropertiesProvider
    new RawRorConfigBasedCoreFactory(RorMode.Plugin)
  }

  private val zonedDateTime = ZonedDateTime.of(2019, 1, 1, 0, 1, 59, 0, ZoneId.of("+1"))

  "Auditing settings" should {
    "be optional" when {
      "audit collector is not configured at all (by default - disabled)" in {
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

          assertSettingsPresent[DefaultAuditLogSerializer](
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

          assertSettingsPresent[DefaultAuditLogSerializer](
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

          assertSettingsPresent[QueryAuditLogSerializer](
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

          assertSettingsPresent[DeprecatedAuditLogSerializerAdapter[_]](
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

          assertSettingsPresent[DefaultAuditLogSerializer](
            config,
            expectedIndexName = "readonlyrest_audit-2018-12-31",
            expectedAuditCluster = RemoteAuditCluster(NonEmptyList.one(Uri.parse("1.1.1.1").get))
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

          assertSettingsPresent[QueryAuditLogSerializer](
            config,
            expectedIndexName = "custom_template_20181231",
            expectedAuditCluster = RemoteAuditCluster(NonEmptyList.one(Uri.parse("1.1.1.1").get))
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

          assertSettingsPresent[DefaultAuditLogSerializer](
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

          assertSettingsPresent[DefaultAuditLogSerializer](
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

          assertSettingsPresent[QueryAuditLogSerializer](
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

          assertSettingsPresent[DeprecatedAuditLogSerializerAdapter[_]](
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
              |  audit_cluster: ["1.1.1.1"]
              |
              |  access_control_rules:
              |
              |  - name: test_block
              |    type: allow
              |    auth_key: admin:container
              |
          """.stripMargin)

          assertSettingsPresent[DefaultAuditLogSerializer](
            config,
            expectedIndexName = "readonlyrest_audit-2018-12-31",
            expectedAuditCluster = RemoteAuditCluster(NonEmptyList.one(Uri.parse("1.1.1.1").get))
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

          assertSettingsPresent[QueryAuditLogSerializer](
            config,
            expectedIndexName = "custom_template_20181231",
            expectedAuditCluster = RemoteAuditCluster(NonEmptyList.one(Uri.parse("1.1.1.1").get))
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
    inside(core) { case Right(CoreSettings(_, None)) => }
  }

  private def assertSettingsPresent[EXPECTED_SERIALIZER: ClassTag](config: RawRorConfig,
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
    inside(core) { case Right(CoreSettings(_, Some(auditingSettings))) =>
      auditingSettings.rorAuditIndexTemplate.indexName(zonedDateTime.toInstant) should be(indexName(expectedIndexName))
      auditingSettings.logSerializer shouldBe a[EXPECTED_SERIALIZER]
      auditingSettings.auditCluster shouldBe expectedAuditCluster
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
