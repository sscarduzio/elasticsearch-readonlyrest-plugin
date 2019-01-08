package tech.beshu.ror.acl.factory.decoders

import org.scalatest.Matchers._
import tech.beshu.ror.TestsUtils.jsonFrom
import tech.beshu.ror.acl.blocks.rules.AuthKeySha512Rule
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.MalformedValue
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.commons.aDomain.AuthData

class AuthKeySha512RuleSettingsTests extends RuleSettingsDecoderTest[AuthKeySha512Rule] {

  "An AuthKeySha512Rule" should {
    "be able to read from config" when {
      "only one SHA512 auth key is defined" in {
        assertDecodingSuccess(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_sha512: "688aced3690d88ae780979585038f4c5072a8f7fce6a8edfdc8e63a6fbf1d5d4c420f60352f90e25d7932234eef8cf17c9d8c9e52dbdce4bad62477d396aa187"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.authKey should be(AuthData("688aced3690d88ae780979585038f4c5072a8f7fce6a8edfdc8e63a6fbf1d5d4c420f60352f90e25d7932234eef8cf17c9d8c9e52dbdce4bad62477d396aa187"))
          }
        )
      }
    }
    "not be able to read from config" when {
      "no SHA512 auth key is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_sha512:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue(jsonFrom(
              """
                |[{
                |  "auth_key_sha512" : null
                |}]
              """.stripMargin))))
          }
        )
      }
    }
  }
}
