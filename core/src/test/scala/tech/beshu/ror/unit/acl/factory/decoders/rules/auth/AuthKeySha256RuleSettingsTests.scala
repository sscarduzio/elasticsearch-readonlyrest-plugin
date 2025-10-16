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
package tech.beshu.ror.unit.acl.factory.decoders.rules.auth
import org.scalatest.matchers.should.Matchers.*
import tech.beshu.ror.accesscontrol.blocks.rules.auth.AuthKeyHashingRule.HashedCredentials
import tech.beshu.ror.accesscontrol.blocks.rules.auth.AuthKeySha256Rule
import tech.beshu.ror.accesscontrol.domain.User
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason.{MalformedValue, Message}
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.unit.acl.factory.decoders.rules.BaseRuleSettingsDecoderTest
import tech.beshu.ror.utils.TestsUtils.unsafeNes

class AuthKeySha256RuleSettingsTests extends BaseRuleSettingsDecoderTest[AuthKeySha256Rule] {

  "An AuthKeySha256Rule" should {
    "be able to be loaded from settings" when {
      "SHA256 auth key is defined (all hashed syntax)" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_sha256: "bdf2f78928097ae90a029c33fe06a83e3a572cb48371fb2de290d1c2ffee010b"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.credentials should be {
              HashedCredentials.HashedUserAndPassword("bdf2f78928097ae90a029c33fe06a83e3a572cb48371fb2de290d1c2ffee010b")
            }
          }
        )
      }
      "SHA256 auth key is defined (password hashed syntax)" in {
        assertDecodingSuccess(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_sha256: "user1:bdf2f78928097ae90a029c33fe06a83e3a572cb48371fb2de290d1c2ffee010b"
              |
              |""".stripMargin,
          assertion = rule => {
            rule.settings.credentials should be {
              HashedCredentials.HashedOnlyPassword(User.Id("user1"), "bdf2f78928097ae90a029c33fe06a83e3a572cb48371fb2de290d1c2ffee010b")
            }
          }
        )
      }
    }
    "not be able to be loaded from settings" when {
      "no SHA256 auth key is defined" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_sha256:
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue.fromString(
              """auth_key_sha256: null
                |""".stripMargin
            )))
          }
        )
      }
      "SHA256 auth key is empty" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_sha256: ""
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(MalformedValue.fromString(
              """auth_key_sha256: ""
                |""".stripMargin
            )))
          }
        )
      }
      "SHA256 auth user part is empty" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_sha256: ":bdf2f78928097ae90a029c33fe06a83e3a572cb48371fb2de290d1c2ffee010b"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(Message(
              "Auth key rule credentials malformed (expected two non-empty values separated with colon)"
            )))
          }
        )
      }
      "SHA256 auth secret part is empty" in {
        assertDecodingFailure(
          yaml =
            """
              |readonlyrest:
              |
              |  access_control_rules:
              |
              |  - name: test_block1
              |    auth_key_sha256: "user1:"
              |
              |""".stripMargin,
          assertion = errors => {
            errors should have size 1
            errors.head should be (RulesLevelCreationError(Message(
              "Auth key rule credentials malformed (expected two non-empty values separated with colon)"
            )))
          }
        )
      }
    }
  }
}
