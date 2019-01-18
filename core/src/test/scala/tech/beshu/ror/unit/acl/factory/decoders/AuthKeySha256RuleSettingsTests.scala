package tech.beshu.ror.unit.acl.factory.decoders

import org.scalatest.Matchers._
import tech.beshu.ror.TestsUtils.jsonFrom
import tech.beshu.ror.acl.blocks.rules.AuthKeySha256Rule
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.MalformedValue
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.aDomain.AuthData

class AuthKeySha256RuleSettingsTests extends RuleSettingsDecoderTest[AuthKeySha256Rule] {

  "An AuthKeySha256Rule" should {
    "be able to read from config" when {
      "only one SHA256 auth key is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_sha256: "bdf2f78928097ae90a029c33fe06a83e3a572cb48371fb2de290d1c2ffee010b"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.authKey should be(AuthData("bdf2f78928097ae90a029c33fe06a83e3a572cb48371fb2de290d1c2ffee010b"))
          }
        )
      }
    }
    "not be able to read from config" when {
      "no SHA256 auth key is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_sha256:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue(jsonFrom(
              """
                |[{
                |  "auth_key_sha256" : null
                |}]
              """.stripMargin))))
          }
        )
      }
    }
  }
}
