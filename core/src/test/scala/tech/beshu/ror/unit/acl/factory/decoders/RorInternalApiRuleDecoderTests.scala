package tech.beshu.ror.unit.acl.factory.decoders

import java.time.Instant

import eu.timepit.refined.auto._
import org.scalatest.Matchers._
import tech.beshu.ror.accesscontrol.blocks.rules.RorInternalApiRule
import tech.beshu.ror.accesscontrol.blocks.rules.RorInternalApiRule.InternalApiAccess
import tech.beshu.ror.accesscontrol.domain.{IndexName, RorAuditIndexTemplate, RorConfigurationIndex}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError

class RorInternalApiRuleDecoderTests extends BaseRuleSettingsDecoderTest[RorInternalApiRule] {

  "A RorInternalApiRule" should {
    "be able to be loaded from config" when {
      "'allow' is configured as access type" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    ror_internal_api: allow
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.access should be(InternalApiAccess.Allow)
            rule.settings.configurationIndex should be (RorConfigurationIndex(IndexName(".readonlyrest")))
            rule.settings.indexAuditTemplate should be (None)
          }
        )
      }
      "'forbid' is configured as access type" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    ror_internal_api: forbid
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.access should be(InternalApiAccess.Forbid)
            rule.settings.configurationIndex should be (RorConfigurationIndex(IndexName(".readonlyrest")))
            rule.settings.indexAuditTemplate should be (None)
          }
        )
      }
      "default audit collector is enabled" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  audit_collector: true
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    ror_internal_api: Forbid
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.access should be(InternalApiAccess.Forbid)
            rule.settings.configurationIndex should be (RorConfigurationIndex(IndexName(".readonlyrest")))

            val now = Instant.now()
            val expectedIndexAuditTemplate = RorAuditIndexTemplate.from("'readonlyrest_audit-'yyyy-MM-dd").right.get
            rule.settings.indexAuditTemplate.map(_.indexName(now)) should be (Some(expectedIndexAuditTemplate.indexName(now)))
          }
        )
      }
      "custom audit collector is enabled" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  audit_collector: true
              |  audit_index_template: "'ROR-'yyyyMM"
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    ror_internal_api: Forbid
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.access should be(InternalApiAccess.Forbid)
            rule.settings.configurationIndex should be (RorConfigurationIndex(IndexName(".readonlyrest")))

            val now = Instant.now()
            val expectedIndexAuditTemplate = RorAuditIndexTemplate.from("'ROR-'yyyyMM").right.get
            rule.settings.indexAuditTemplate.map(_.indexName(now)) should be (Some(expectedIndexAuditTemplate.indexName(now)))
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "'unknown' access type is set" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    ror_internal_api: unknown
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(Message(
              "Unknown ROR internal access type 'unknown'. Possible options: 'allow', 'forbid'"
            )))
          }
        )
      }
    }
  }
}
