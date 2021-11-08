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

import java.time.{Clock, ZoneId, ZonedDateTime}

import eu.timepit.refined.auto._
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.mocks.NoOpMocksProvider
import tech.beshu.ror.accesscontrol.domain.{IndexName, RorConfigurationIndex}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.AuditingSettingsCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.{CoreSettings, RawRorConfigBasedCoreFactory}
import tech.beshu.ror.audit.adapters.DeprecatedAuditLogSerializerAdapter
import tech.beshu.ror.audit.instances.{DefaultAuditLogSerializer, QueryAuditLogSerializer}
import tech.beshu.ror.boot.RorMode
import tech.beshu.ror.mocks.{MockHttpClientsFactory, MockLdapConnectionPoolProvider}
import tech.beshu.ror.providers._
import tech.beshu.ror.utils.TestsUtils._

class AuditingSettingsTests extends AnyWordSpec with Inside {

  private val factory = {
    implicit val clock: Clock = Clock.systemUTC()
    implicit val uuidProvider: UuidProvider = JavaUuidProvider
    implicit val envVarsProvider: EnvVarsProvider = OsEnvVarsProvider
    implicit val propertiesProvider: PropertiesProvider = JvmPropertiesProvider
    new RawRorConfigBasedCoreFactory(RorMode.Plugin)
  }

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
      "audit collector is disabled" in {
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
    }
    "be able to be loaded from config" when {
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
          val zonedDateTime = ZonedDateTime.of(2019, 1, 1, 0, 1, 59, 0, ZoneId.of("+1"))
          auditingSettings.rorAuditIndexTemplate.indexName(zonedDateTime.toInstant) should be(indexName("readonlyrest_audit-2018-12-31"))
          auditingSettings.logSerializer shouldBe a[DefaultAuditLogSerializer]
        }
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
          val zonedDateTime = ZonedDateTime.of(2019, 1, 1, 0, 1, 59, 0, ZoneId.of("+1"))
          auditingSettings.rorAuditIndexTemplate.indexName(zonedDateTime.toInstant) should be(indexName("custom_template_20181231"))
          auditingSettings.logSerializer shouldBe a[DefaultAuditLogSerializer]
        }
      }
      "custom serializer in set" in {
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
          val zonedDateTime = ZonedDateTime.of(2019, 1, 1, 0, 1, 59, 0, ZoneId.of("+1"))
          auditingSettings.rorAuditIndexTemplate.indexName(zonedDateTime.toInstant) should be(indexName("readonlyrest_audit-2018-12-31"))
          auditingSettings.logSerializer shouldBe a[QueryAuditLogSerializer]
        }
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
          val zonedDateTime = ZonedDateTime.of(2019, 1, 1, 0, 1, 59, 0, ZoneId.of("+1"))
          auditingSettings.rorAuditIndexTemplate.indexName(zonedDateTime.toInstant) should be(indexName("readonlyrest_audit-2018-12-31"))
          auditingSettings.logSerializer shouldBe a[DeprecatedAuditLogSerializerAdapter[_]]
        }
      }
    }
    "not be able to be loaded from config" when {
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
          errors.head should be (AuditingSettingsCreationError(Message(
            "Class tech.beshu.ror.accesscontrol.blocks.RuleOrdering is not a subclass of tech.beshu.ror.audit.AuditLogSerializer or tech.beshu.ror.requestcontext.AuditLogSerializer"
          )))
        }
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
          errors.head should be (AuditingSettingsCreationError(Message(
            "Illegal pattern specified for audit_index_template. Have you misplaced quotes? Search for 'DateTimeFormatter patterns' to learn the syntax. Pattern was: invalid pattern error: Unknown pattern letter: i"
          )))
        }
      }
    }
  }
}
