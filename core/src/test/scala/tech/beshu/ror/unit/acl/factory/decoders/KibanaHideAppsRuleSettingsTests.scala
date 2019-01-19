package tech.beshu.ror.unit.acl.factory.decoders

import cats.data.NonEmptySet
import org.scalatest.Matchers._

import tech.beshu.ror.acl.blocks.rules.KibanaHideAppsRule
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.MalformedValue
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.aDomain.KibanaApp
import tech.beshu.ror.acl.orders._

class KibanaHideAppsRuleSettingsTests extends RuleSettingsDecoderTest[KibanaHideAppsRule] {

  "A KibanaHideAppsRule" should {
    "be able to be loaded from config" when {
      "only one kibana app is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana_hide_apps: "app1"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.kibanaAppsToHide should be(NonEmptySet.one(KibanaApp("app1")))
          }
        )
      }
      "several kibana apps are defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana_hide_apps: [app1, app2]
              |
              |""".stripMargin,
          assertion = rule => {
            val apps = NonEmptySet.of(KibanaApp("app1"), KibanaApp("app2"))
            rule.settings.kibanaAppsToHide should be(apps)
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "no kibana app is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana_hide_apps:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue(
              """readonlyrest:
                |  access_control_rules:
                |  - kibana_hide_apps: null
                |""".stripMargin)))
          }
        )
      }
    }
  }
}
