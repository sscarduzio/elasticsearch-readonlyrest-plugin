package tech.beshu.ror.unit.acl.factory.decoders

import org.scalatest.Matchers._
import tech.beshu.ror.acl.blocks.rules.AuthKeySha1Rule
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.MalformedValue
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.aDomain.AuthData

class AuthKeySha1RuleSettingsTests extends RuleSettingsDecoderTest[AuthKeySha1Rule] {

  "An AuthKeySha1Rule" should {
    "be able to be loaded from config" when {
      "only one SHA1 auth key is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_sha1: "d27aaf7fa3c1603948bb29b7339f2559dc02019a"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.authKey should be(AuthData("d27aaf7fa3c1603948bb29b7339f2559dc02019a"))
          }
        )
      }
    }
    "not be able to be loaded from config" when {
      "no SHA1 auth key is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_sha1:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue(
              """readonlyrest:
                |  access_control_rules:
                |  - auth_key_sha1: null
                |""".stripMargin
            )))
          }
        )
      }
    }
  }
}
