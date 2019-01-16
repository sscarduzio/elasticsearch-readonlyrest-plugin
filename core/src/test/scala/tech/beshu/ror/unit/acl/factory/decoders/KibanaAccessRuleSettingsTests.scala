package tech.beshu.ror.unit.acl.factory.decoders

import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import tech.beshu.ror.TestsUtils.jsonFrom
import tech.beshu.ror.commons.aDomain.{IndexName, KibanaAccess}
import tech.beshu.ror.unit.acl.blocks.Variable.ValueWithVariable
import tech.beshu.ror.unit.acl.blocks.{Const, Variable}
import tech.beshu.ror.unit.acl.blocks.rules.KibanaAccessRule
import tech.beshu.ror.unit.acl.factory.RorAclFactory.AclCreationError.Reason.MalformedValue
import tech.beshu.ror.unit.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError

class KibanaAccessRuleSettingsTests extends RuleSettingsDecoderTest[KibanaAccessRule] with MockFactory {

  "A KibanaAccess" should {
    "be able to read from config" when {
      "ro access is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana_access: ro
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.access should be(KibanaAccess.RO)
            rule.settings.kibanaIndex should be(Const(IndexName(".kibana")))
            rule.settings.kibanaMetadataEnabled should be (true)
          }
        )
      }
      "rw access is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana_access: rw
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.access should be(KibanaAccess.RW)
            rule.settings.kibanaIndex should be(Const(IndexName(".kibana")))
            rule.settings.kibanaMetadataEnabled should be (true)
          }
        )
      }
      "ro_strict access is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana_access: RO_STRICT
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.access should be(KibanaAccess.ROStrict)
            rule.settings.kibanaIndex should be(Const(IndexName(".kibana")))
            rule.settings.kibanaMetadataEnabled should be (true)
          }
        )
      }
      "admin access is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana_access: admin
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.access should be(KibanaAccess.Admin)
            rule.settings.kibanaIndex should be(Const(IndexName(".kibana")))
            rule.settings.kibanaMetadataEnabled should be (true)
          }
        )
      }
      "some access is defined with changed default kibana index" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana_access: admin
              |    kibana_index: ".kibana_admin"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.access should be(KibanaAccess.Admin)
            rule.settings.kibanaIndex should be(Const(IndexName(".kibana_admin")))
            rule.settings.kibanaMetadataEnabled should be (true)
          }
        )
      }
      "some access is defined with changed default kibana index to variable" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana_access: admin
              |    kibana_index: .kibana_@{user}
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.access should be(KibanaAccess.Admin)
            val variable = Variable(ValueWithVariable(".kibana_@{user}"), rv => Right(IndexName(rv.value)))
            rule.settings.kibanaIndex shouldBe a [Variable[_]]
            rule.settings.kibanaIndex.asInstanceOf[Variable[IndexName]].representation should be(variable.representation)
            rule.settings.kibanaMetadataEnabled should be (true)
          }
        )
      }
      "some access is defined with disabled ror metadata" in {
        System.setProperty("com.readonlyrest.kibana.metadata", "false")
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana_access: admin
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.access should be(KibanaAccess.Admin)
            rule.settings.kibanaIndex should be(Const(IndexName(".kibana")))
            rule.settings.kibanaMetadataEnabled should be (false)
          }
        )
      }
    }
    "not be able to read from config" when {
      "no access is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    kibana_access:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be(RulesLevelCreationError(MalformedValue(jsonFrom(
              """
                |[{
                |  "kibana_access" : null
                |}]
              """.stripMargin))))
          }
        )
      }
    }
  }
}
