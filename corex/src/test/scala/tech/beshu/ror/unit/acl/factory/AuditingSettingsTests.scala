package tech.beshu.ror.unit.acl.factory

import java.time.{Clock, ZoneId, ZonedDateTime}

import org.scalatest.Matchers._
import org.scalatest.{Inside, WordSpec}
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.AuditingSettingsCreationError
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.{CoreFactory, CoreSettings}
import tech.beshu.ror.acl.utils.{JavaEnvVarsProvider, JavaUuidProvider, StaticVariablesResolver, UuidProvider}
import tech.beshu.ror.audit.adapters.DeprecatedAuditLogSerializerAdapter
import tech.beshu.ror.audit.instances.{DefaultAuditLogSerializer, QueryAuditLogSerializer}
import tech.beshu.ror.mocks.MockHttpClientsFactory

class AuditingSettingsTests extends WordSpec with Inside {

  private val factory = {
    implicit val clock: Clock = Clock.systemUTC()
    implicit val uuidProvider: UuidProvider = JavaUuidProvider
    implicit val resolver: StaticVariablesResolver = new StaticVariablesResolver(JavaEnvVarsProvider)
    new CoreFactory
  }

  "Auditing settings" should {
    "be optional" when {
      "audit collector is not configured at all (by default - disabled)" in {
        val yaml =
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
        val core = factory.createCoreFrom(yaml, MockHttpClientsFactory)
        inside(core) { case Right(CoreSettings(_, _, None)) => }
      }
      "audit collector is disabled" in {
        val yaml =
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
          """.stripMargin
        val core = factory.createCoreFrom(yaml, MockHttpClientsFactory)
        inside(core) { case Right(CoreSettings(_, _, None)) => }
      }
    }
    "be able to be loaded from config" when {
      "audit collector is enabled" in {
        val yaml =
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
          """.stripMargin
        val core = factory.createCoreFrom(yaml, MockHttpClientsFactory)
        inside(core) { case Right(CoreSettings(_, _, Some(auditingSettings))) =>
          val zonedDateTime = ZonedDateTime.of(2019, 1, 1, 0, 1, 59, 0, ZoneId.of("+1"))
          auditingSettings.indexNameFormatter.format(zonedDateTime.toInstant) should be("readonlyrest_audit-2018-12-31")
          auditingSettings.logSerializer shouldBe a[DefaultAuditLogSerializer]
        }
      }
      "custom audit index name is set" in {
        val yaml =
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
          """.stripMargin
        val core = factory.createCoreFrom(yaml, MockHttpClientsFactory)
        inside(core) { case Right(CoreSettings(_, _, Some(auditingSettings))) =>
          val zonedDateTime = ZonedDateTime.of(2019, 1, 1, 0, 1, 59, 0, ZoneId.of("+1"))
          auditingSettings.indexNameFormatter.format(zonedDateTime.toInstant) should be("custom_template_20181231")
          auditingSettings.logSerializer shouldBe a[DefaultAuditLogSerializer]
        }
      }
      "custom serializer in set" in {
        val yaml =
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
          """.stripMargin
        val core = factory.createCoreFrom(yaml, MockHttpClientsFactory)
        inside(core) { case Right(CoreSettings(_, _, Some(auditingSettings))) =>
          val zonedDateTime = ZonedDateTime.of(2019, 1, 1, 0, 1, 59, 0, ZoneId.of("+1"))
          auditingSettings.indexNameFormatter.format(zonedDateTime.toInstant) should be("readonlyrest_audit-2018-12-31")
          auditingSettings.logSerializer shouldBe a[QueryAuditLogSerializer]
        }
      }
      "deprecated custom serializer is set" in {
        val yaml =
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
          """.stripMargin
        val core = factory.createCoreFrom(yaml, MockHttpClientsFactory)
        inside(core) { case Right(CoreSettings(_, _, Some(auditingSettings))) =>
          val zonedDateTime = ZonedDateTime.of(2019, 1, 1, 0, 1, 59, 0, ZoneId.of("+1"))
          auditingSettings.indexNameFormatter.format(zonedDateTime.toInstant) should be("readonlyrest_audit-2018-12-31")
          auditingSettings.logSerializer shouldBe a[DeprecatedAuditLogSerializerAdapter[_]]
        }
      }
    }
    "not be able to be loaded from config" when {
      "not supported custom serializer is set" in {
        val yaml =
          """
            |readonlyrest:
            |  audit_collector: true
            |  audit_serializer: "tech.beshu.ror.acl.blocks.RuleOrdering"
            |
            |  access_control_rules:
            |
            |  - name: test_block
            |    type: allow
            |    auth_key: admin:container
            |
          """.stripMargin
        val core = factory.createCoreFrom(yaml, MockHttpClientsFactory)
        inside(core) { case Left(errors) =>
          errors.length should be(1)
          errors.head should be (AuditingSettingsCreationError(Message(
            "Class tech.beshu.ror.acl.blocks.RuleOrdering is not a subclass of tech.beshu.ror.audit.AuditLogSerializer or tech.beshu.ror.requestcontext.AuditLogSerializer"
          )))
        }
      }
      "custom audit index name pattern is invalid" in {
        val yaml =
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
          """.stripMargin
        val core = factory.createCoreFrom(yaml, MockHttpClientsFactory)
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
